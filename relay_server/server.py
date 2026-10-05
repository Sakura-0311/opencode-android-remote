import hmac
import asyncio
import uuid
import ipaddress
import hashlib
import json
import logging
import os
import secrets
import sys
import time
from collections import deque
from contextlib import asynccontextmanager
from typing import Dict, Set, Optional, Tuple
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import JSONResponse
import uvicorn

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [OpenCodeRelay] %(message)s"
)
logger = logging.getLogger("OpenCodeRelay")

# v4.7.0/R8: 版本号单一来源
RELAY_VERSION = "4.7.0"

app = FastAPI(title="OpenCode Cloud Relay Server", version=RELAY_VERSION)

# ==============================================================================
# v4.0: 协议版本与能力协商（唯一破坏性版本）
# 客户端连接后必须先发 hello，服务端回 hello_ack；之后再走 auth。
# 无 hello 的连接（v2 旧客户端）直接被拒绝（4401 + hello_required）。
# ==============================================================================
PROTOCOL_VERSION = 4
SERVER_CAPABILITIES = [
    "hello",            # hello/hello_ack 能力协商
    "multi_desktop",    # 多 desktop 共存（按 device_id 区分）
    "device_id_revoke", # 按 device_id 撤销
    "room_buffer",      # 房间消息缓冲与断线补发
    "pairing",          # 扫码配对
    "desktop_routing",  # v3.1: source/target_device_id 定向路由
    "e2ee",             # E2EE: mobile↔desktop 端到端加密（relay 盲转发）
]

# ==============================================================================
# B-8: 建房管理令牌（防房间抢注）
# 若设置 RELAY_ADMIN_TOKEN，则 desktop 首次建房必须在 auth 消息中携带相符的
# admin_token；未设置时保持旧行为（仅打警告日志），保证向后兼容。
# ==============================================================================
RELAY_ADMIN_TOKEN = os.getenv("RELAY_ADMIN_TOKEN", "")
if RELAY_ADMIN_TOKEN:
    logger.info("B-8: RELAY_ADMIN_TOKEN 已启用，建房需携带管理令牌")
else:
    logger.warning("B-8: 未设置 RELAY_ADMIN_TOKEN，任何 desktop 均可建房（有被抢注风险），建议生产环境配置")

# ==============================================================================
# P0-3: 内存滑动窗口防爆破与限流器
# ==============================================================================
TRUSTED_PROXIES = set(filter(None, os.getenv("TRUSTED_PROXIES", "127.0.0.1,::1").split(",")))

def get_client_ip(ws: WebSocket) -> str:
    direct = ws.client.host if ws.client else "unknown"
    if direct in TRUSTED_PROXIES:
        xff = ws.headers.get("x-forwarded-for")
        if xff:
            # v4.7.0/R6: 取最右侧第一个不在 TRUSTED_PROXIES 里的地址——
            # 客户端自带的 XFF 最左侧值可伪造，不能直接取第一个
            for ip in reversed([p.strip() for p in xff.split(",") if p.strip()]):
                if ip not in TRUSTED_PROXIES:
                    return ip
    return direct

class RateLimiter:
    """
    轻量级内存防爆破与连接频次限流器：
    - 单 IP 每分钟连接数限制
    - 连续认证失败封禁策略（防配对码/Secret暴力枚举）
    """
    def __init__(self, max_connections_per_min: int = 30, max_auth_fails: int = 5, jail_seconds: int = 900):
        self.max_connections_per_min = max_connections_per_min
        self.max_auth_fails = max_auth_fails
        self.jail_seconds = jail_seconds
        
        # ip -> [timestamp, timestamp, ...]
        self.ip_connection_timestamps: Dict[str, list] = {}
        # ip -> fail_count
        self.ip_auth_fails: Dict[str, int] = {}
        # ip -> jail_until_timestamp
        self.ip_jailed_until: Dict[str, float] = {}

    def is_jailed(self, ip: str) -> bool:
        now = time.time()
        jailed_until = self.ip_jailed_until.get(ip, 0)
        if now < jailed_until:
            return True
        elif ip in self.ip_jailed_until:
            del self.ip_jailed_until[ip]
            self.ip_auth_fails[ip] = 0
        return False

    def cleanup(self) -> None:
        """v2.4: 定期清理过期 IP 记录，防止长期运行内存缓慢增长。"""
        now = time.time()
        for ip in list(self.ip_connection_timestamps.keys()):
            ts = [t for t in self.ip_connection_timestamps[ip] if now - t < 60]
            if ts:
                self.ip_connection_timestamps[ip] = ts
            else:
                del self.ip_connection_timestamps[ip]
        for ip in list(self.ip_jailed_until.keys()):
            if now >= self.ip_jailed_until[ip]:
                del self.ip_jailed_until[ip]
                self.ip_auth_fails.pop(ip, None)
        # 认证失败计数超过封禁期两倍未再犯，清零
        for ip in list(self.ip_auth_fails.keys()):
            if ip not in self.ip_jailed_until and self.ip_auth_fails[ip] == 0:
                del self.ip_auth_fails[ip]

    def check_connection_allowed(self, ip: str) -> bool:
        if self.is_jailed(ip):
            return False
        now = time.time()
        timestamps = self.ip_connection_timestamps.get(ip, [])
        # 保留最近 60 秒内的连接记录
        timestamps = [t for t in timestamps if now - t < 60]
        self.ip_connection_timestamps[ip] = timestamps
        if len(timestamps) >= self.max_connections_per_min:
            return False
        timestamps.append(now)
        return True

    def record_auth_failure(self, ip: str):
        current_fails = self.ip_auth_fails.get(ip, 0) + 1
        self.ip_auth_fails[ip] = current_fails
        logger.warning(f"Auth failure from IP {ip} ({current_fails}/{self.max_auth_fails})")
        if current_fails >= self.max_auth_fails:
            self.ip_jailed_until[ip] = time.time() + self.jail_seconds
            logger.error(f"IP {ip} has been jailed for {self.jail_seconds}s due to repeated auth failures.")

    def record_auth_success(self, ip: str):
        if ip in self.ip_auth_fails:
            del self.ip_auth_fails[ip]


rate_limiter = RateLimiter()

# ==============================================================================
# P0-1 & P1-7: 连接与房间管理器（带严格鉴权与应用层心跳）
# ==============================================================================
class ClientSession:
    def __init__(self, websocket: WebSocket, client_type: str, account_id: str, client_ip: str):
        self.websocket = websocket
        self.client_type = client_type
        self.account_id = account_id
        self.client_ip = client_ip
        self.last_pong_time = time.time()
        self.is_authenticated = False
        # v3.0.2/B2: 本次认证时间（主 desktop 掉线回退时选最近认证者）
        self.auth_time = 0.0
        self.last_active = 0.0  # v3.1: desktop 最近活动（在线列表用）
        # v2.4: 设备身份（设备密钥登录时填充）
        self.device_id = ""
        self.device_name = str()
        # v4.7.0/R2: 每会话滑动窗口限速（时间戳队列）
        self.msg_timestamps: deque = deque()

    def check_rate_limit(self, max_msgs: int = 100, window_sec: int = 10) -> bool:
        """v4.7.0/R2: 滑动窗口限速。超限返回 False（调用方丢弃消息）。"""
        now = time.time()
        q = self.msg_timestamps
        while q and now - q[0] > window_sec:
            q.popleft()
        if len(q) >= max_msgs:
            return False
        q.append(now)
        return True


class RoomBuffer:
    """
    P1-6: 房间消息缓冲三重限制（数量 + 时间 + 总字节数），任一达到即从最旧开始淘汰。
    - max_count: 最多保留条数（默认 1000）
    - max_age_sec: 消息最长保留秒数（默认 300 = 5 分钟）
    - max_bytes: 缓冲总字节上限（默认 4MB）
    条目按 relay_seq 单调递增；淘汰旧消息不影响序号连续性，超窗口时由 resync_required 告知客户端。
    """
    def __init__(self, max_count: int = 1000, max_age_sec: int = 300, max_bytes: int = 4 * 1024 * 1024):
        self.max_count = max_count
        self.max_age_sec = max_age_sec
        self.max_bytes = max_bytes
        self._buf: deque = deque()
        self._bytes: int = 0

    def append(self, seq: int, msg: str) -> None:
        now = time.time()
        size = len(msg.encode("utf-8"))
        self._buf.append({"seq": seq, "msg": msg, "ts": now, "size": size})
        self._bytes += size
        self._evict(now)

    def _evict(self, now: float) -> None:
        while self._buf and (
            len(self._buf) > self.max_count
            or self._bytes > self.max_bytes
            or (now - self._buf[0]["ts"]) > self.max_age_sec
        ):
            old = self._buf.popleft()
            self._bytes -= old["size"]

    def __iter__(self):
        return iter(self._buf)

    def __len__(self):
        return len(self._buf)


class ConnectionManager:
    def __init__(self):
        # account_id -> {
        #   "secret_hash": str,
        #   "desktop": Optional[ClientSession],
        #   "mobiles": Set[ClientSession],
        #   "msg_buffer": RoomBuffer,  # v1.6 P0 断线恢复 + P1-6 三重限制的房间消息缓冲
        #   "next_seq": int,      # v1.6: 下一条消息序号
        #   "device_secrets": Dict[str, dict],  # v1.6 P0 扫码配对：设备密钥
        # }
        self.rooms: Dict[str, dict] = {}
        # websocket -> ClientSession
        self.sessions: Dict[WebSocket, ClientSession] = {}
        # P1-6: 缓冲三重上限（数量/时间/字节），可用环境变量覆盖
        self.room_buffer_max_count = int(os.getenv("RELAY_ROOM_BUFFER_MAX_COUNT", "1000"))
        self.room_buffer_max_age = int(os.getenv("RELAY_ROOM_BUFFER_MAX_AGE_SEC", "300"))
        self.room_buffer_max_bytes = int(os.getenv("RELAY_ROOM_BUFFER_MAX_BYTES", str(4 * 1024 * 1024)))
        # v1.6 P0 扫码配对：pairing_token -> 配对会话
        self.pairing_sessions: Dict[str, dict] = {}
        self.pairing_ttl = int(os.getenv("RELAY_PAIRING_TTL", "120"))
        # v2.4: 记录近期见过的客户端 IP（用于代理/反代配置提示）
        self.seen_ips: Dict[str, float] = {}
        # v2.2.1-B: 设备密钥持久化（房间销毁/relay 重启后仍可用设备密钥重连）
        self.state_file = os.getenv(
            "RELAY_STATE_FILE",
            os.path.expanduser("~/.config/opencode-remote/relay_state.json"),
        )
        self._saved_state = self._load_state()

    # ---------------- v2.2.1-B: 设备密钥持久化 ----------------
    STATE_SCHEMA_VERSION = 1

    def _load_state(self) -> dict:
        """从磁盘加载已保存的房间密钥状态。返回 {account_id: {...}}。"""
        try:
            if not os.path.exists(self.state_file):
                return {}
            with open(self.state_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict) or data.get("schema_version") != self.STATE_SCHEMA_VERSION:
                logger.warning(f"[v2.2.1-B] relay_state schema 不匹配，已忽略: {self.state_file}")
                return {}
            rooms = data.get("rooms", {})
            return rooms if isinstance(rooms, dict) else {}
        except Exception as e:
            logger.warning(f"[v2.2.1-B] 加载 relay_state 失败: {e}")
            return {}

    def _save_state(self, _force: bool = False) -> None:
        """把内存中的 device_secrets/secret_hash 与已保存的合并后原子写入磁盘（0600）。

        v4.7.0/R5: 节流（5 秒内只写一次，尾调用由心跳 flush）+ 清理 90 天未活跃设备。
        """
        now = time.time()
        if not _force and now - getattr(self, "_last_save_ts", 0) < 5:
            self._save_pending = True
            return
        self._last_save_ts = now
        self._save_pending = False
        try:
            merged = dict(self._saved_state)
            # v4.7.0/R5: 设备密钥 90 天未活跃则过期清理
            expiry = now - 90 * 24 * 3600
            for account_id, room in self.rooms.items():
                secrets = room.get("device_secrets", {})
                for k in [k for k, v in secrets.items()
                          if v.get("last_active", v.get("created_at", 0)) < expiry]:
                    del secrets[k]
                merged[account_id] = {
                    "secret_hash": room.get("secret_hash", ""),
                    "device_secrets": secrets,
                    "updated_at": now,
                }
            data = {"schema_version": self.STATE_SCHEMA_VERSION, "rooms": merged}
            d = os.path.dirname(self.state_file)
            if d:
                os.makedirs(d, mode=0o700, exist_ok=True)
            tmp = self.state_file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f)
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.state_file)
            self._saved_state = merged
        except Exception as e:
            logger.warning(f"[v2.2.1-B] 保存 relay_state 失败: {e}")

    def _restore_room_secrets(self, account_id: str, room: dict, secret_hash: str) -> None:
        """房间重建时从磁盘恢复 device_secrets（仅当主密钥哈希一致时）。"""
        saved = self._saved_state.get(account_id)
        if not saved:
            return
        if not hmac.compare_digest(saved.get("secret_hash", ""), secret_hash):
            logger.warning(f"[v2.2.1-B] room {account_id} 主密钥已变更，旧设备密钥不恢复")
            return
        restored = saved.get("device_secrets") or {}
        if restored:
            # v2.4: 旧版本条目没有 device_id，补一个（保证撤销按 ID 可用）
            for h, info in restored.items():
                if isinstance(info, dict) and not info.get("device_id"):
                    info["device_id"] = uuid.uuid4().hex
            room["device_secrets"] = dict(restored)
            logger.info(f"[v2.2.1-B] room {account_id} 恢复 {len(restored)} 个设备密钥")
    # ---------------- v2.2.1-B 结束 ----------------

    def get_session(self, ws: WebSocket) -> Optional[ClientSession]:
        return self.sessions.get(ws)

    def register_authenticated(self, session: ClientSession, secret: str, admin_token: str = "") -> Tuple[bool, str]:
        """
        认证并注册进入指定房间。
        - Desktop 注册：初始化房间 secret_hash 或校验现有 secret_hash；
        - Mobile 注册：必须核对已存 room 的 secret_hash。
        - B-8: 若配置了 RELAY_ADMIN_TOKEN，首次建房必须携带相符的 admin_token。
        """
        account_id = session.account_id
        secret_hash = hashlib.sha256(secret.encode("utf-8")).hexdigest()

        if account_id not in self.rooms:
            # 只有 Desktop 才能首次创建并绑定房间密码
            if session.client_type != "desktop":
                return False, "Room does not exist yet. Please start desktop agent first."
            # B-8: 建房管理令牌校验
            if RELAY_ADMIN_TOKEN:
                if not admin_token or not hmac.compare_digest(admin_token, RELAY_ADMIN_TOKEN):
                    logger.warning(f"B-8: Room creation denied for {account_id}: invalid/missing admin token")
                    return False, "ROOM_CREATE_DENIED: admin token required to create room."
            self.rooms[account_id] = {
                "secret_hash": secret_hash,
                "desktop": None,
                # v4.0: 多 desktop 共存，按 device_id 区分（desktop 必须上报 device_id）。
                # "desktop" 保留为"主 desktop"（最近认证/活跃），旧单连接逻辑读它即可。
                "desktops": {},
                "mobiles": set(),
                # v1.6 P0 断线恢复 + P1-6 三重限制缓冲
                "msg_buffer": RoomBuffer(
                    max_count=self.room_buffer_max_count,
                    max_age_sec=self.room_buffer_max_age,
                    max_bytes=self.room_buffer_max_bytes,
                ),
                "next_seq": 1,
                # v2.2.1-C: 序号纪元——房间重建时序号归零，客户端用 epoch 区分
                "room_epoch": secrets.token_hex(8),
                # v1.6 P0 扫码配对：secret_hash -> {device_name, created_at}
                "device_secrets": {},
            }
            # v2.2.1-B: 从磁盘恢复该房间的设备密钥（主密钥一致时）
            self._restore_room_secrets(account_id, self.rooms[account_id], secret_hash)
            self._save_state()
            logger.info(f"New room created: {account_id} by desktop.")

        room = self.rooms[account_id]

        # 密码比对：主密钥或已配对的设备密钥均可
        # v1.6 P0 扫码配对：设备密钥与具体设备绑定
        is_master = hmac.compare_digest(room["secret_hash"], secret_hash)
        device_info = None if is_master else room.get("device_secrets", {}).get(secret_hash)
        if not is_master and not device_info:
            return False, "Invalid secret for account_id."
        if device_info:
            session.device_name = device_info.get("device_name", "unknown")
            # v2.4: 记录设备 ID，供"仅可撤销自己"鉴权
            session.device_id = device_info.get("device_id", "")

        session.is_authenticated = True
        # v3.0.2/B2: 记录认证时间，供主 desktop 回退排序
        session.auth_time = time.time()
        self.sessions[session.websocket] = session

        if session.client_type == "desktop":
            # v4.0: 按 device_id 区分多 desktop；同 device_id 重连才顶替旧连接（旧单连接逻辑保留）
            # v4 要求 desktop 必须上报 device_id（auth 时已校验），此处不再设 "legacy" 回退键。
            desk_key = getattr(session, "device_id", "")
            old_desktop = room["desktops"].get(desk_key)
            if old_desktop and old_desktop.websocket != session.websocket:
                # 只有携带了有效 secret 的新 desktop 才能顶替旧 desktop
                try:
                    asyncio.create_task(old_desktop.websocket.close(code=1000, reason="Replaced by new authenticated desktop session"))
                except Exception:
                    pass
                if old_desktop.websocket in self.sessions:
                    del self.sessions[old_desktop.websocket]
            room["desktops"][desk_key] = session
            # 主 desktop = 最近认证的（mobile 消息路由目标）
            room["desktop"] = session
            logger.info(f"Desktop successfully authenticated for room: {account_id} (device_id={desk_key}, desktops={len(room['desktops'])})")
            asyncio.create_task(self.broadcast_status(account_id, desktop_online=True))
        else:
            room["mobiles"].add(session)
            logger.info(f"Mobile successfully authenticated for room: {account_id} (Active mobiles: {len(room['mobiles'])})")
            # 即刻告知当前 desktop 是否在线
            is_desktop_online = bool(room.get("desktops"))
            asyncio.create_task(session.websocket.send_text(json.dumps({
                "type": "system_status",
                "desktop_online": is_desktop_online,
                "message": "Authenticated successfully with relay server."
            })))

        return True, "OK"

    # ==========================================================================
    # v1.6 P0 扫码配对
    # ==========================================================================
    def create_pairing(self, account_id: str, desktop_name: str,
                       e2ee_pubkey: str = "", desktop_device_id: str = "",
                       e2ee_pubkey_sig: str = "") -> Tuple[bool, dict]:
        """
        桌面端创建一次性配对会话。
        返回的 pairing_token 有效期短（默认 120s）、一次性使用，
        二维码中不包含长期 Secret。
        E2EE: desktop 可在创建时上报 e2ee_pubkey（base64 X25519 公钥），
        存内存配对会话（随过期丢弃，不落盘、不校验），mobile 认领成功后经
        pair_success 带回。
        """
        self._cleanup_expired_pairings()
        if account_id not in self.rooms:
            return False, {"error": "Room does not exist."}
        token = secrets.token_urlsafe(24)
        now = time.time()
        self.pairing_sessions[token] = {
            "account_id": account_id,
            "desktop_name": desktop_name or "Desktop",
            "created_at": now,
            "expires_at": now + self.pairing_ttl,
            "claimed": False,
            # E2EE: desktop 公钥（base64），只透传，不校验、不落盘
            "e2ee_pubkey": (e2ee_pubkey or "")[:256],
            # E2EE: 建配对的 desktop 的 device_id（auth 时上报），mobile 用它绑定公钥
            "desktop_device_id": (desktop_device_id or "")[:64],
            # v4.3 M-2: desktop 公钥 HMAC 签名，只透传，不校验、不落盘
            "e2ee_pubkey_sig": (e2ee_pubkey_sig or "")[:128],
        }
        logger.info(f"v1.6: pairing session created for room {account_id} (ttl={self.pairing_ttl}s)")
        return True, {
            "pairing_token": token,
            "expires_at": now + self.pairing_ttl,
            "ttl_seconds": self.pairing_ttl,
        }

    def claim_pairing(self, account_id: str, pairing_token: str, device_name: str,
                      e2ee_pubkey: str = "") -> Tuple[bool, dict]:
        """
        移动端凭配对码认领，成功后签发设备专用密钥。
        设备密钥与具体设备绑定，避免一个 Secret 无限复用。
        E2EE: mobile 可在认领时上报 e2ee_pubkey（base64 X25519 公钥），只透传
        给 desktop（device_paired），不校验、不落盘；desktop 在 create_pairing
        时上报的公钥经结果带回，由调用方放入 pair_success。
        """
        self._cleanup_expired_pairings()
        ps = self.pairing_sessions.get(pairing_token)
        if not ps or ps["claimed"] or ps["account_id"] != account_id:
            return False, {"error": "Invalid or expired pairing code."}
        if time.time() > ps["expires_at"]:
            del self.pairing_sessions[pairing_token]
            return False, {"error": "Pairing code expired."}
        room = self.rooms.get(account_id)
        if not room:
            return False, {"error": "Room does not exist."}
        ps["claimed"] = True
        device_secret = secrets.token_urlsafe(32)
        secret_hash = hashlib.sha256(device_secret.encode("utf-8")).hexdigest()
        now_ts = time.time()
        # v4.7.0/R4: 设备名重名时自动加后缀（"Android"、"Android (2)"…），展示用；
        # 身份识别一律按 device_id
        base_name = (device_name or "Android")[:64]
        taken = {i.get("device_name") for i in room.get("device_secrets", {}).values()}
        uniq_name = base_name
        _n = 2
        while uniq_name in taken:
            uniq_name = f"{base_name} ({_n})"
            _n += 1
        room.setdefault("device_secrets", {})[secret_hash] = {
            # v2.4: 设备以 device_id（UUID）标识，名称仅展示
            "device_id": uuid.uuid4().hex,
            "device_name": uniq_name,
            "created_at": now_ts,
            "last_active": now_ts,
        }
        logger.info(f"v1.6: device '{device_name}' paired to room {account_id}")
        self._save_state()  # v2.2.1-B: 新配对设备立即落盘
        dev_entry = room["device_secrets"][secret_hash]
        return True, {
            "device_secret": device_secret,
            "device_id": dev_entry.get("device_id", ""),
            # v4.7.0/R4: 返回去重后的展示名
            "device_name": uniq_name,
            "account_id": account_id,
            "desktop_name": ps["desktop_name"],
            # E2EE: desktop 公钥（create_pairing 时上报，若无则为空）
            "e2ee_pubkey": ps.get("e2ee_pubkey", "") or "",
            # E2EE: 建配对的 desktop 的 device_id，mobile 用它绑定公钥
            "desktop_device_id": ps.get("desktop_device_id", "") or "",
            # v4.3 M-2: desktop 公钥 HMAC 签名（create_pairing 时上报）
            "e2ee_pubkey_sig": ps.get("e2ee_pubkey_sig", "") or "",
            # E2EE: mobile 公钥（本次认领上报，只透传）
            "mobile_e2ee_pubkey": (e2ee_pubkey or "")[:256],
        }

    def revoke_device(self, account_id: str, device_id: str = "", device_name: str = "") -> tuple:
        """v2.4: 撤销指定设备的配对授权。
        按 device_id 匹配（device_name 仅兼容旧 App）。
        返回 (ok, kicked_sessions)：kicked_sessions 为该设备当前在线的 mobile 会话，
        由调用方关闭其连接（被撤销设备不再能重连）。
        """
        room = self.rooms.get(account_id)
        if not room:
            return False, []
        dev_secrets = room.get("device_secrets", {})
        target_hash, target_info = None, None
        for h, info in list(dev_secrets.items()):
            if device_id and info.get("device_id") == device_id:
                target_hash, target_info = h, info
                break
        if target_hash is None and device_name:
            for h, info in list(dev_secrets.items()):
                if info.get("device_name") == device_name:
                    target_hash, target_info = h, info
                    break
        if target_hash is None:
            return False, []
        del dev_secrets[target_hash]
        logger.info(f"v2.4: device '{target_info.get('device_name')}' revoked from room {account_id}")
        self._save_state()  # v2.2.1-B: 撤销后落盘
        # v2.4: 主动断开该设备的在线连接
        kicked = []
        for m in list(room.get("mobiles", set())):
            mid = getattr(m, "device_id", "")
            mname = getattr(m, "device_name", "")
            if (device_id and mid == device_id) or (not device_id and mname == device_name):
                kicked.append(m)
        return True, kicked

    def rename_device(self, account_id: str, old_name: str, new_name: str, device_id: str = "") -> bool:
        """重命名设备（v2.4: 优先按 device_id 匹配）。"""
        room = self.rooms.get(account_id)
        if not room or not new_name or len(new_name) > 64:
            return False
        for info in room.get("device_secrets", {}).values():
            if (device_id and info.get("device_id") == device_id) or \
               (not device_id and info.get("device_name") == old_name):
                info["device_name"] = new_name
                logger.info(f"v2.4: device renamed '{old_name}' -> '{new_name}' in {account_id}")
                self._save_state()  # v2.2.1-B: 重命名后落盘
                return True
        return False

    def list_devices(self, account_id: str) -> list:
        """
        v1.6 P0 多设备管理：列出已配对设备（含在线状态、最近活动）。
        """
        room = self.rooms.get(account_id)
        if not room:
            return []
        # 当前在线的设备 ID 集合（v4.7.0/R4: 只按 device_id 判定，同名不再串台）
        online_ids = set()
        for m in room.get("mobiles", set()):
            did = getattr(m, "device_id", None)
            if did:
                online_ids.add(did)
        result = []
        for info in room.get("device_secrets", {}).values():
            name = info.get("device_name", "?")
            did = info.get("device_id", "")
            result.append({
                "device_id": did,
                "device_name": name,
                "created_at": info.get("created_at", 0),
                "is_online": bool(did and did in online_ids),
                "last_active": info.get("last_active", info.get("created_at", 0)),
            })
        return result

    def list_desktops(self, account_id: str):
        """v3.1: 在线 desktop 列表（device_id、名称缩写、最近活动、是否主）。"""
        room = self.rooms.get(account_id)
        if not room:
            return []
        primary = room.get("desktop")
        result = []
        for did, sess in room.get("desktops", {}).items():
            name = getattr(sess, "device_name", "") or did or "Desktop"
            result.append({
                "device_id": did,
                "device_name": name,
                "is_primary": sess is primary,
                "last_active": getattr(sess, "last_active", 0.0) or getattr(sess, "auth_time", 0.0),
            })
        # 按最近活动倒序
        result.sort(key=lambda d: d["last_active"], reverse=True)
        return result

    def mark_device_active(self, account_id: str, device_id: str):
        """更新设备最近活动时间。v4.7.0/R4: 按 device_id 识别（同名设备不再串台）。"""
        room = self.rooms.get(account_id)
        if not room or not device_id:
            return
        for info in room.get("device_secrets", {}).values():
            if info.get("device_id") == device_id:
                info["last_active"] = time.time()
                break

    def _cleanup_expired_pairings(self):
        now = time.time()
        expired = [t for t, ps in self.pairing_sessions.items()
                   if ps["claimed"] or now > ps["expires_at"]]
        for t in expired:
            del self.pairing_sessions[t]
    def disconnect(self, ws: WebSocket):
        session = self.sessions.pop(ws, None)
        if not session:
            return

        account_id = session.account_id
        if account_id in self.rooms:
            room = self.rooms[account_id]
            if session.client_type == "desktop":
                # v3.0: 按 device_id 移除
                for k, v in list(room.get("desktops", {}).items()):
                    if v == session:
                        del room["desktops"][k]
                        break
                if room.get("desktop") == session:
                    # 主 desktop 断开：推举另一个在线 desktop 为主
                    remaining = list(room.get("desktops", {}).values())
                    # v3.0.2/B2: 按最近认证时间回退（此前取字典第一项，语义不一致）
                    remaining.sort(key=lambda s: getattr(s, "auth_time", 0.0), reverse=True)
                    room["desktop"] = remaining[0] if remaining else None
                    new_primary = getattr(room["desktop"], "device_id", "") if room["desktop"] else ""
                    logger.info(f"Desktop disconnected from room: {account_id} (remaining={len(remaining)}, new_primary={new_primary})")
                if not room.get("desktops"):
                    asyncio.create_task(self.broadcast_status(account_id, desktop_online=False))
            else:
                room["mobiles"].discard(session)
                logger.info(f"Mobile disconnected from room: {account_id}")

            # 房间内无人时清理连接与缓冲；v2.2.1-B: 先把密钥落盘，设备密钥不随房间销毁丢失
            if not room.get("desktops") and not room["mobiles"]:
                self._save_state()
                del self.rooms[account_id]
                logger.info(f"Room {account_id} destroyed (all clients disconnected).")

    async def broadcast_status(self, account_id: str, desktop_online: bool):
        if account_id in self.rooms:
            msg = json.dumps({
                "type": "desktop_status",
                "online": desktop_online
            })
            for m_session in list(self.rooms[account_id]["mobiles"]):
                try:
                    await m_session.websocket.send_text(msg)
                except Exception:
                    pass

    async def route_message(self, sender_session: ClientSession, message_str: str):
        if not sender_session.is_authenticated:
            return

        # v4.7.0/R2: 每会话限速（100 条 / 10 秒），超限丢弃
        if not sender_session.check_rate_limit():
            logger.warning(f"R2: 会话限速丢弃消息 "
                           f"({sender_session.client_type}/{sender_session.account_id})")
            return

        account_id = sender_session.account_id
        if account_id not in self.rooms:
            return

        # v1.6 P0 多设备管理：更新设备活跃时间（v4.7.0/R4: 按 device_id）
        _did = getattr(sender_session, "device_id", None)
        if _did:
            self.mark_device_active(account_id, _did)
        # v3.1: desktop 会话的最近活动（在线 desktop 列表用）
        if sender_session.client_type == "desktop":
            sender_session.last_active = time.time()

        room = self.rooms[account_id]
        if sender_session.client_type == "mobile":
            # v3.1: 可选 target_device_id 定向路由；缺省仍走主 desktop
            # v4.7.0/R3: 快路径——消息里没有 target_device_id 子串时跳过 json 解析
            target_id = ""
            if '"target_device_id"' in message_str:
                try:
                    _m = json.loads(message_str)
                    if isinstance(_m, dict):
                        target_id = str(_m.get("target_device_id", "") or "")
                except Exception:
                    pass
            desktop = None
            if target_id:
                cand = room.get("desktops", {}).get(target_id)
                if cand and getattr(cand, "websocket", None):
                    desktop = cand
                else:
                    # 目标不存在或不在线：明确报错（带目标标识）
                    try:
                        await sender_session.websocket.send_text(json.dumps({
                            "type": "error",
                            "code": "DESKTOP_OFFLINE",
                            "target_device_id": target_id,
                            "message": f"目标电脑（{target_id[:8]}…）当前不在线，无法执行指令。"
                        }))
                    except Exception:
                        pass
                    return
            else:
                desktop = room.get("desktop")
            if desktop and desktop.websocket:
                try:
                    # v4.7.0/R1: 单发也加超时，慢 desktop 不卡住 mobile 的读循环
                    await asyncio.wait_for(desktop.websocket.send_text(message_str),
                                           timeout=10)
                except asyncio.TimeoutError:
                    logger.warning(f"R1: 发送到 desktop 超时 ({account_id})")
                except Exception as e:
                    logger.error(f"Error routing mobile -> desktop in {account_id}: {e}")
            else:
                # 告知手机端电脑当前不在线
                try:
                    await sender_session.websocket.send_text(json.dumps({
                        "type": "error",
                        "code": "DESKTOP_OFFLINE",
                        "message": "电脑端 OpenCode 桥接未连接，无法执行指令。"
                    }))
                except Exception:
                    pass
        elif sender_session.client_type == "desktop":
            # v1.6 P0 断线恢复：分配单调序号并入环形缓冲，供移动端断线重连补发
            seq = room.get("next_seq", 1)
            room["next_seq"] = seq + 1
            try:
                msg_obj = json.loads(message_str)
                if isinstance(msg_obj, dict):
                    msg_obj["relay_seq"] = seq
                    # v3.1: 记录消息来源 desktop（缓冲条目一并带上；旧 App 忽略）
                    _src_id = getattr(sender_session, "device_id", "") or ""
                    if _src_id:
                        msg_obj["source_device_id"] = _src_id
                    sequenced_str = json.dumps(msg_obj)
                else:
                    sequenced_str = message_str
            except Exception:
                sequenced_str = message_str
            buf = room.get("msg_buffer")
            if buf is not None:
                buf.append(seq, sequenced_str)
            # v4.7.0/R1: 扇出改 gather+超时——慢连接不再阻塞整个房间，
            # 发送超时（10s）的连接直接断开
            async def _fanout(sess, data):
                try:
                    await asyncio.wait_for(sess.websocket.send_text(data), timeout=10)
                except asyncio.TimeoutError:
                    logger.warning(f"R1: 发送超时，断开慢连接 "
                                   f"({getattr(sess, 'device_name', '?')})")
                    try:
                        await sess.websocket.close(code=1013, reason="send timeout")
                    except Exception:
                        pass
                except Exception as e:
                    logger.error(f"Error routing desktop -> mobile in {account_id}: {e}")
            targets = list(room.get("mobiles", []))
            if targets:
                await asyncio.gather(*(_fanout(s, sequenced_str) for s in targets),
                                     return_exceptions=True)

    async def check_heartbeats(self):
        """
        P1-7: 应用层心跳检测协程
        每 25 秒向所有连接发送 ping，超时未响应 pong 则主动断开
        """
        now = time.time()
        dead_sockets = []
        for ws, session in list(self.sessions.items()):
            # 超过 35 秒（25s + 10s 宽限）无响应
            if now - session.last_pong_time > 35:
                dead_sockets.append((ws, session))
            else:
                try:
                    await ws.send_text(json.dumps({"type": "ping", "timestamp": now}))
                except Exception:
                    dead_sockets.append((ws, session))

        for ws, session in dead_sockets:
            logger.warning(f"Closing zombie connection for {session.client_type} in {session.account_id} (Heartbeat timeout)")
            try:
                await ws.close(code=1001, reason="Heartbeat timeout")
            except Exception:
                pass
            self.disconnect(ws)

        # v4.7.0/R5: flush 被节流拦下的存盘（丢到线程池，不阻塞事件循环）
        if getattr(self, "_save_pending", False):
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, lambda: self._save_state(_force=True))


manager = ConnectionManager()

# 后台心跳任务循环
_heartbeat_rounds = 0

async def heartbeat_background_task():
    global _heartbeat_rounds
    while True:
        try:
            await asyncio.sleep(25)
            await manager.check_heartbeats()
            _heartbeat_rounds += 1
            # v2.4: 限流器定期清理（每轮）
            rate_limiter.cleanup()
            # v2.4: 每约 5 分钟检查一次代理配置提示
            if _heartbeat_rounds % 12 == 0:
                _check_proxy_hint()
        except Exception as e:
            logger.error(f"Error in heartbeat task: {e}")


def _is_private_ip(ip: str) -> bool:
    try:
        return ipaddress.ip_address(ip).is_private
    except Exception:
        return False


def _check_proxy_hint() -> None:
    """v2.4: 若所有连接都来自同一私网地址，提示配置 TRUSTED_PROXIES。"""
    now = time.time()
    ips = [ip for ip, ts in manager.seen_ips.items() if now - ts < 600]
    # 清理过期
    for ip in list(manager.seen_ips.keys()):
        if now - manager.seen_ips[ip] >= 600:
            del manager.seen_ips[ip]
    if len(ips) == 1 and _is_private_ip(ips[0]):
        default_proxies = {"127.0.0.1", "::1"}
        if TRUSTED_PROXIES == default_proxies:
            logger.warning(
                f"v2.4: 过去 10 分钟所有连接都来自同一私网地址 {ips[0]}，"
                "relay 可能在 Docker/反代后面。限流按 IP 生效，一个人输错 5 次会封所有人 15 分钟，"
                "建议在环境变量 TRUSTED_PROXIES 中配置反代出口 IP（见 docs/SECURITY.md）。"
            )

# v4.7.0/R9: lifespan 替代已弃用的 @app.on_event("startup")；
# 后台任务引用保存在集合里，防被 GC 回收
_bg_tasks: set = set()


def _require_admin_token() -> None:
    """v4.7.0/P1-6: 建房令牌强制——未设置/占位符/太短则拒绝启动。"""
    token = (RELAY_ADMIN_TOKEN or "").strip()
    placeholders = {"", "changeme", "change-me", "换成你自己的随机字符串",
                    "your-secret-here", "test", "password", "123456"}
    if token.lower() in placeholders or len(token) < 16:
        raise RuntimeError(
            "P1-6: RELAY_ADMIN_TOKEN 未设置、为占位符或太短（<16 字符），拒绝启动。\n"
            "请设置强随机令牌后重试：\n"
            "  export RELAY_ADMIN_TOKEN=$(openssl rand -hex 24)\n"
            "  python server.py --admin-token $(openssl rand -hex 24)\n"
            "  Docker: 在 .env 里填 RELAY_ADMIN_TOKEN（见 .env.example）")


@asynccontextmanager
async def _lifespan(app):
    _require_admin_token()
    _bg_tasks.add(asyncio.ensure_future(heartbeat_background_task()))
    yield
    for t in _bg_tasks:
        t.cancel()


app.router.lifespan_context = _lifespan

@app.get("/")
def index():
    return {
        "status": "ok",
        "service": "OpenCode Secure Relay Server",
        "security": "Transport Layer Encryption (WSS/TLS) Supported",
        "version": RELAY_VERSION
    }

# v4.0: 运行统计。本机/内网运维用，不含敏感信息。
@app.get("/api/stats")
def api_stats():
    rooms = manager.rooms
    return {
        "status": "ok",
        "protocol_version": PROTOCOL_VERSION,
        "rooms": len(rooms),
        "online_sessions": len(manager.sessions),
    }

# B2: 崩溃上报接收端已抽到 crash_report.py（ACRA，脱敏字段，存本地文件）
try:
    from crash_report import register_routes as _register_crash_report_routes
except ImportError:  # python -m uvicorn relay_server.server:app（包模式）
    from relay_server.crash_report import register_routes as _register_crash_report_routes
_register_crash_report_routes(app, get_client_ip)

@app.websocket("/ws")
@app.websocket("/ws/{path_account_id}/{path_client_type}")
async def websocket_endpoint(
    websocket: WebSocket,
    path_account_id: Optional[str] = None,
    path_client_type: Optional[str] = None
):
    client_ip = get_client_ip(websocket)

    # 1. 检查 IP 限流
    if not rate_limiter.check_connection_allowed(client_ip):
        await websocket.close(code=4429, reason="Too many requests or IP jailed.")
        logger.warning(f"Rejected connection from {client_ip}: Rate limit exceeded or jailed.")
        return

    await websocket.accept()
    # v2.4: 记录客户端 IP（10 分钟窗口）
    manager.seen_ips[client_ip] = time.time()

    # 2. 等待首条认证消息（10 秒超时）
    session: Optional[ClientSession] = None
    try:
        raw_auth_msg = await asyncio.wait_for(websocket.receive_text(), timeout=10.0)
        auth_data = json.loads(raw_auth_msg)
        # v4.7.0/R7: 首帧必须为 JSON 对象
        if not isinstance(auth_data, dict):
            raise ValueError("first frame must be a JSON object")
    except asyncio.TimeoutError:
        logger.warning(f"Handshake timeout from {client_ip}: No auth message within 10s.")
        rate_limiter.record_auth_failure(client_ip)
        await websocket.close(code=4408, reason="Authentication timeout (10s)")
        return
    except Exception as e:
        logger.warning(f"Malformed auth message from {client_ip}: {e}")
        rate_limiter.record_auth_failure(client_ip)
        await websocket.close(code=4400, reason="Invalid auth message format")
        return

    msg_type = auth_data.get("type")
    account_id = auth_data.get("account_id") or path_account_id
    client_type = auth_data.get("client_type") or path_client_type
    secret = auth_data.get("secret", "")

    # v1.6 P0 扫码配对：未认证连接可用配对码认领设备密钥（一次性、短期有效）
    if msg_type == "pair_claim":
        pairing_token = auth_data.get("pairing_token", "")
        device_name = auth_data.get("device_name", "Android")[:64]
        # E2EE: mobile 公钥（可选），只透传给 desktop，不校验、不落盘
        mobile_e2ee_pubkey = str(auth_data.get("e2ee_pubkey", "") or "")[:256]
        ok, result = manager.claim_pairing(
            account_id or "", pairing_token, device_name,
            e2ee_pubkey=mobile_e2ee_pubkey)
        if ok:
            # E2EE: pair_success 带回 desktop 在 create_pairing 时上报的公钥（若有）
            pair_success_msg = {
                "type": "pair_success",
                "account_id": result["account_id"],
                "desktop_name": result["desktop_name"],
                "device_secret": result["device_secret"],
                "message": "配对成功，请使用设备密钥重新连接。"
            }
            if result.get("e2ee_pubkey"):
                pair_success_msg["e2ee_pubkey"] = result["e2ee_pubkey"]
            # v4.3 M-2: 公钥 HMAC 签名透传给 mobile 校验
            if result.get("e2ee_pubkey_sig"):
                pair_success_msg["e2ee_pubkey_sig"] = result["e2ee_pubkey_sig"]
            # E2EE: desktop 的 device_id，mobile 用它把公钥绑定到设备；缺失则跳过绑定
            if result.get("desktop_device_id"):
                pair_success_msg["desktop_device_id"] = result["desktop_device_id"]
            await websocket.send_text(json.dumps(pair_success_msg))
            # 通知桌面端有新设备配对
            room = manager.rooms.get(account_id or "")
            if room and room.get("desktop"):
                try:
                    # E2EE: device_paired 带 mobile 公钥（若有），desktop 用它做 ECDH
                    device_paired_msg = {
                        "type": "device_paired",
                        # v4.7.0/R4: 用去重后的展示名
                        "device_name": result.get("device_name", device_name),
                        # v4.6.0: 带上 relay 分配的 device_id，desktop 用它做 E2EE
                        # peer id 和 AAD（与手机加密时的 sender 一致）
                        "device_id": result.get("device_id", ""),
                        "message": f"新设备已配对：{result.get('device_name', device_name)}"
                    }
                    if result.get("mobile_e2ee_pubkey"):
                        device_paired_msg["e2ee_pubkey"] = result["mobile_e2ee_pubkey"]
                    await room["desktop"].websocket.send_text(json.dumps(device_paired_msg))
                except Exception:
                    pass
        else:
            rate_limiter.record_auth_failure(client_ip)
            await websocket.send_text(json.dumps({
                "type": "pair_error",
                "message": result.get("error", "配对失败")
            }))
        await websocket.close(code=1000, reason="Pairing done")
        return

    # v4.0: hello 能力协商为强制。首帧不是 hello 的连接（v2 旧客户端）直接拒绝，
    # 给出明确的 hello_required 错误与 4401，供客户端报 PROTOCOL_MISMATCH。
    if msg_type != "hello":
        logger.warning(f"v4.0: 拒绝无 hello 的连接 from {client_ip} (type={msg_type})")
        await websocket.send_text(json.dumps({
            "type": "hello_required",
            "message": "Protocol v4 requires hello handshake before auth. "
                       "v2 clients are no longer supported; upgrade relay/agent/app to v4.0+.",
            "v": PROTOCOL_VERSION,
            "server_capabilities": SERVER_CAPABILITIES,
        }))
        await websocket.close(code=4401, reason="v4 requires hello")
        return
    client_v = auth_data.get("v", 0)
    client_caps = auth_data.get("capabilities", [])
    logger.info(f"v4.0 hello from {client_ip}: client_v={client_v} caps={client_caps}")
    await websocket.send_text(json.dumps({
        "type": "hello_ack",
        "v": PROTOCOL_VERSION,
        "server_capabilities": SERVER_CAPABILITIES,
    }))
    # 等待真正的 auth 帧
    try:
        auth_raw = await asyncio.wait_for(websocket.receive_text(), timeout=15.0)
        auth_data = json.loads(auth_raw)
        # v4.7.0/R7: 首帧 JSON 必须为对象（如 [] 会导致后续 .get 抛未捕获异常）
        if not isinstance(auth_data, dict):
            raise ValueError("auth frame must be a JSON object")
    except Exception:
        # v4.7.0/R7: hello 后等 auth 的超时/异常同样计入认证失败
        rate_limiter.record_auth_failure(client_ip)
        await websocket.close(code=4401, reason="hello without auth")
        return
    msg_type = auth_data.get("type")
    account_id = auth_data.get("account_id") or path_account_id
    client_type = auth_data.get("client_type") or path_client_type
    secret = auth_data.get("secret", "")
    # v4.7.0/R7: 关键字段必须为字符串（防非字符串类型导致下游异常）
    for _f in ("account_id", "client_type", "secret", "device_name", "admin_token"):
        _v = auth_data.get(_f)
        if _v is not None and not isinstance(_v, str):
            logger.warning(f"R7: 非法字段类型 {_f}={type(_v).__name__} from {client_ip}")
            rate_limiter.record_auth_failure(client_ip)
            await websocket.close(code=4400, reason=f"field {_f} must be string")
            return

    if msg_type != "auth" or not account_id or client_type not in ["desktop", "mobile"] or not secret:
        logger.warning(f"Auth rejected from {client_ip}: missing required auth fields.")
        rate_limiter.record_auth_failure(client_ip)
        await websocket.send_text(json.dumps({
            "type": "auth_error",
            "message": "First message must be valid auth with account_id, secret, and client_type."
        }))
        await websocket.close(code=4401, reason="Unauthorized")
        return

    session = ClientSession(websocket, client_type, account_id, client_ip)
    # v4.0: desktop 必须在 auth 包中上报 device_id（多 desktop 区分键，无回退）。
    if client_type == "desktop":
        session.device_id = str(auth_data.get("device_id", ""))[:64]
        if not session.device_id:
            logger.warning(f"v4.0: 拒绝无 device_id 的 desktop from {client_ip}")
            rate_limiter.record_auth_failure(client_ip)
            await websocket.send_text(json.dumps({
                "type": "auth_error",
                "message": "Protocol v4 requires device_id for desktop clients."
            }))
            await websocket.close(code=4401, reason="v4 requires device_id")
            return
    # B-8: 透传 admin_token 供建房校验
    success, reason = manager.register_authenticated(session, secret, auth_data.get("admin_token", ""))
    if not success:
        rate_limiter.record_auth_failure(client_ip)
        try:
            await websocket.send_text(json.dumps({
                "type": "auth_error",
                "message": reason
            }))
            await websocket.close(code=4401, reason=reason)
        except Exception:
            pass
        return

    # 认证成功，清除失败计数
    rate_limiter.record_auth_success(client_ip)
    await websocket.send_text(json.dumps({
        "type": "auth_ok",
        "account_id": account_id,
        "client_type": client_type,
        # v2.2.1-C: 序号纪元（可选字段，旧 App 忽略）
        "room_epoch": manager.rooms.get(account_id, {}).get("room_epoch", ""),
        # v3.0: 协议版本与能力（可选字段，旧客户端忽略）
        "v": PROTOCOL_VERSION,
        "server_capabilities": SERVER_CAPABILITIES,
    }))

    # v1.6 P0 断线恢复：移动端携带 last_relay_seq 重连时，补发缓冲中缺失的消息
    if client_type == "mobile":
        try:
            last_seq = int(auth_data.get("last_relay_seq", 0) or 0)
        except (TypeError, ValueError):
            last_seq = 0
        room = manager.rooms.get(account_id)
        if room:
            missed = [m for m in room.get("msg_buffer", []) if m["seq"] > last_seq]
            # 超过缓冲窗口：明确告知需要重同步，而非静默丢失
            if last_seq > 0 and not missed and room.get("next_seq", 1) - 1 > last_seq:
                await websocket.send_text(json.dumps({
                    "type": "resync_required",
                    "message": "断线时间过长，本地缓存已过期，请重新同步会话状态。",
                    "last_server_seq": room.get("next_seq", 1) - 1
                }))
            for m in missed:
                try:
                    await websocket.send_text(m["msg"])
                except Exception:
                    break
            if missed:
                logger.info(f"v1.6: replayed {len(missed)} buffered messages to mobile in {account_id} (after seq {last_seq})")
        # 告知客户端当前服务端序号，便于幂等去重
        try:
            await websocket.send_text(json.dumps({
                "type": "seq_sync",
                "server_seq": manager.rooms.get(account_id, {}).get("next_seq", 1) - 1,
                # v2.2.1-C: 序号纪元（可选字段）
                "room_epoch": manager.rooms.get(account_id, {}).get("room_epoch", ""),
            }))
        except Exception:
            pass

    # 3. 进入双向业务消息收发循环
    try:
        while True:
            text = await websocket.receive_text()
            # 处理应用层与底层心跳
            if text == "ping":
                session.last_pong_time = time.time()
                await websocket.send_text("pong")
                continue
            
            try:
                parsed = json.loads(text)
                if parsed.get("type") == "pong":
                    session.last_pong_time = time.time()
                    continue
                elif parsed.get("type") == "ping":
                    session.last_pong_time = time.time()
                    await websocket.send_text(json.dumps({"type": "pong", "timestamp": time.time()}))
                    continue
                # v1.6 P0 扫码配对：桌面端配对管理（已认证）
                elif parsed.get("type") == "create_pairing" and session.client_type == "desktop":
                    ok, result = manager.create_pairing(
                        session.account_id, parsed.get("desktop_name", "Desktop"),
                        # E2EE: desktop 公钥只收下存内存，不校验内容
                        e2ee_pubkey=str(parsed.get("e2ee_pubkey", "") or ""),
                        desktop_device_id=getattr(session, "device_id", "") or "",
                        # v4.3 M-2: HMAC 签名只透传，不校验
                        e2ee_pubkey_sig=str(parsed.get("e2ee_pubkey_sig", "") or ""))
                    await websocket.send_text(json.dumps({
                        "type": "pairing_created" if ok else "pairing_error",
                        **result
                    }))
                    continue
                # v1.6 P0 多设备管理：桌面端和移动端均可管理设备
                elif parsed.get("type") == "list_devices":
                    await websocket.send_text(json.dumps({
                        "type": "device_list",
                        "devices": manager.list_devices(session.account_id)
                    }))
                    continue
                # v3.1: 在线 desktop 列表（设备页选择目标电脑用）
                elif parsed.get("type") == "list_desktops":
                    await websocket.send_text(json.dumps({
                        "type": "desktop_list",
                        "desktops": manager.list_desktops(session.account_id)
                    }))
                    continue
                elif parsed.get("type") == "revoke_device":
                    # v2.4: 撤销完整化——按 device_id；非桌面设备只能撤销自己；撤销后踢掉在线连接
                    target_id = parsed.get("device_id", "")
                    target_name = parsed.get("device_name", "")
                    if session.client_type != "desktop":
                        own_id = getattr(session, "device_id", "")
                        if not target_id or target_id != own_id:
                            await websocket.send_text(json.dumps({
                                "type": "device_revoke_failed",
                                "device_id": target_id,
                                "device_name": target_name,
                                "message": "non-desktop can only revoke itself",
                            }))
                            continue
                    ok, kicked = manager.revoke_device(
                        session.account_id, device_id=target_id, device_name=target_name)
                    for ks in kicked:
                        try:
                            await ks.websocket.close(code=4401, reason="Device revoked")
                        except Exception:
                            pass
                        manager.disconnect(ks.websocket)
                    await websocket.send_text(json.dumps({
                        "type": "device_revoked" if ok else "device_revoke_failed",
                        "device_id": target_id,
                        "device_name": target_name
                    }))
                    continue
                elif parsed.get("type") == "rename_device" and session.client_type == "desktop":
                    ok = manager.rename_device(
                        session.account_id,
                        parsed.get("old_name", ""),
                        parsed.get("new_name", ""),
                        device_id=parsed.get("device_id", ""))
                    await websocket.send_text(json.dumps({
                        "type": "device_renamed" if ok else "device_rename_failed",
                        "device_name": parsed.get("new_name", "")
                    }))
                    continue
            except Exception:
                pass

            await manager.route_message(session, text)
    except WebSocketDisconnect:
        manager.disconnect(websocket)
    except Exception as e:
        logger.error(f"WebSocket exception for {account_id}: {e}")
        manager.disconnect(websocket)


if __name__ == "__main__":
    # v2.6: CLI 参数，默认从环境变量读取（优先级：CLI > 环境变量 > 内置默认）
    import argparse
    _ap = argparse.ArgumentParser(description="OpenCode Relay Server")
    _ap.add_argument("--port", type=int, default=None, help="监听端口（默认 $PORT）")
    _ap.add_argument("--admin-token", default=None, help="建房管理令牌（默认 $RELAY_ADMIN_TOKEN）")
    _ap.add_argument("--trusted-proxies", default=None,
                     help="信任的代理 IP，逗号分隔（默认 $TRUSTED_PROXIES）")
    # v4.7.0/R2: WebSocket 单帧上限（默认 2 MiB；文件预览上限仅 200 KB）
    _ap.add_argument("--ws-max-size", type=int, default=None,
                     help="WebSocket 单帧字节上限（默认 $WS_MAX_SIZE 或 2097152）")
    _args = _ap.parse_args()
    if _args.admin_token is not None:
        # 同步到环境变量，_require_admin_token() 从环境读取
        os.environ["RELAY_ADMIN_TOKEN"] = _args.admin_token
        globals()["RELAY_ADMIN_TOKEN"] = _args.admin_token
    if _args.trusted_proxies is not None:
        globals()["TRUSTED_PROXIES"] = set(filter(None, _args.trusted_proxies.split(",")))

    # v4.7.0/P1-6: 建房令牌强制——未设置或仍为占位符则拒绝启动
    # （否则任何客户端都能建房/抢注 account_id）
    try:
        _require_admin_token()
    except RuntimeError as e:
        logger.error(str(e))
        raise SystemExit(2)

    host = os.getenv("HOST", "0.0.0.0")
    port = _args.port or int(os.getenv("PORT", "8765"))
    ssl_cert = os.getenv("SSL_CERTFILE")
    ssl_key = os.getenv("SSL_KEYFILE")

    kwargs = {"host": host, "port": port, "workers": 1}
    # v4.7.0/R2: 单帧上限（默认 2 MiB，可用 --ws-max-size / $WS_MAX_SIZE 调整）
    _ws_max = _args.ws_max_size or int(os.getenv("WS_MAX_SIZE", str(2 * 1024 * 1024)))
    kwargs["ws_max_size"] = _ws_max
    logger.info(f"WebSocket 单帧上限: {_ws_max} 字节")
    logger.info("Ensuring single-worker operation to preserve in-memory room state.")
    if ssl_cert and ssl_key and os.path.exists(ssl_cert) and os.path.exists(ssl_key):
        logger.info(f"Starting WSS (TLS) server with cert: {ssl_cert}")
        kwargs["ssl_certfile"] = ssl_cert
        kwargs["ssl_keyfile"] = ssl_key
    else:
        logger.info("Starting unencrypted WS server (recommended to terminate TLS behind a reverse proxy in production).")

    uvicorn.run("server:app", **kwargs)
