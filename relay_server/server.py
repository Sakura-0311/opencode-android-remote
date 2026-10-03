import hmac
import asyncio
import hashlib
import json
import logging
import os
import secrets
import sys
import time
from collections import deque
from typing import Dict, Set, Optional, Tuple
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import JSONResponse
import uvicorn

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [OpenCodeRelay] %(message)s"
)
logger = logging.getLogger("OpenCodeRelay")

app = FastAPI(title="OpenCode Cloud Relay Server", version="1.1.0")

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
            return xff.split(",")[0].strip()
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
                "mobiles": set(),
                # v1.6 P0 断线恢复 + P1-6 三重限制缓冲
                "msg_buffer": RoomBuffer(
                    max_count=self.room_buffer_max_count,
                    max_age_sec=self.room_buffer_max_age,
                    max_bytes=self.room_buffer_max_bytes,
                ),
                "next_seq": 1,
                # v1.6 P0 扫码配对：secret_hash -> {device_name, created_at}
                "device_secrets": {},
            }
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

        session.is_authenticated = True
        self.sessions[session.websocket] = session

        if session.client_type == "desktop":
            old_desktop = room["desktop"]
            if old_desktop and old_desktop.websocket != session.websocket:
                # 只有携带了有效 secret 的新 desktop 才能顶替旧 desktop
                try:
                    asyncio.create_task(old_desktop.websocket.close(code=1000, reason="Replaced by new authenticated desktop session"))
                except Exception:
                    pass
                if old_desktop.websocket in self.sessions:
                    del self.sessions[old_desktop.websocket]
            room["desktop"] = session
            logger.info(f"Desktop successfully authenticated for room: {account_id}")
            asyncio.create_task(self.broadcast_status(account_id, desktop_online=True))
        else:
            room["mobiles"].add(session)
            logger.info(f"Mobile successfully authenticated for room: {account_id} (Active mobiles: {len(room['mobiles'])})")
            # 即刻告知当前 desktop 是否在线
            is_desktop_online = room["desktop"] is not None
            asyncio.create_task(session.websocket.send_text(json.dumps({
                "type": "system_status",
                "desktop_online": is_desktop_online,
                "message": "Authenticated successfully with relay server."
            })))

        return True, "OK"

    # ==========================================================================
    # v1.6 P0 扫码配对
    # ==========================================================================
    def create_pairing(self, account_id: str, desktop_name: str) -> Tuple[bool, dict]:
        """
        桌面端创建一次性配对会话。
        返回的 pairing_token 有效期短（默认 120s）、一次性使用，
        二维码中不包含长期 Secret。
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
        }
        logger.info(f"v1.6: pairing session created for room {account_id} (ttl={self.pairing_ttl}s)")
        return True, {
            "pairing_token": token,
            "expires_at": now + self.pairing_ttl,
            "ttl_seconds": self.pairing_ttl,
        }

    def claim_pairing(self, account_id: str, pairing_token: str, device_name: str) -> Tuple[bool, dict]:
        """
        移动端凭配对码认领，成功后签发设备专用密钥。
        设备密钥与具体设备绑定，避免一个 Secret 无限复用。
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
        room.setdefault("device_secrets", {})[secret_hash] = {
            "device_name": device_name or "Android",
            "created_at": now_ts,
            "last_active": now_ts,
        }
        logger.info(f"v1.6: device '{device_name}' paired to room {account_id}")
        return True, {
            "device_secret": device_secret,
            "account_id": account_id,
            "desktop_name": ps["desktop_name"],
        }

    def revoke_device(self, account_id: str, device_name: str) -> bool:
        """撤销指定设备的配对授权。"""
        room = self.rooms.get(account_id)
        if not room:
            return False
        dev_secrets = room.get("device_secrets", {})
        for h, info in list(dev_secrets.items()):
            if info.get("device_name") == device_name:
                del dev_secrets[h]
                logger.info(f"v1.6: device '{device_name}' revoked from room {account_id}")
                return True
        return False

    def rename_device(self, account_id: str, old_name: str, new_name: str) -> bool:
        """重命名设备。"""
        room = self.rooms.get(account_id)
        if not room or not new_name or len(new_name) > 64:
            return False
        for info in room.get("device_secrets", {}).values():
            if info.get("device_name") == old_name:
                info["device_name"] = new_name
                logger.info(f"v1.6: device renamed '{old_name}' -> '{new_name}' in {account_id}")
                return True
        return False

    def list_devices(self, account_id: str) -> list:
        """
        v1.6 P0 多设备管理：列出已配对设备（含在线状态、最近活动）。
        """
        room = self.rooms.get(account_id)
        if not room:
            return []
        # 当前在线的设备名集合
        online_names = set()
        for m in room.get("mobiles", set()):
            name = getattr(m, "device_name", None)
            if name:
                online_names.add(name)
        result = []
        for info in room.get("device_secrets", {}).values():
            name = info.get("device_name", "?")
            result.append({
                "device_name": name,
                "created_at": info.get("created_at", 0),
                "is_online": name in online_names,
                "last_active": info.get("last_active", info.get("created_at", 0)),
            })
        return result

    def mark_device_active(self, account_id: str, device_name: str):
        """更新设备最近活动时间。"""
        room = self.rooms.get(account_id)
        if not room or not device_name:
            return
        for info in room.get("device_secrets", {}).values():
            if info.get("device_name") == device_name:
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
                if room["desktop"] == session:
                    room["desktop"] = None
                    logger.info(f"Desktop disconnected from room: {account_id}")
                    asyncio.create_task(self.broadcast_status(account_id, desktop_online=False))
            else:
                room["mobiles"].discard(session)
                logger.info(f"Mobile disconnected from room: {account_id}")

            # 房间内无人时，保留房间配置或在无活跃连接时清理
            if room["desktop"] is None and not room["mobiles"]:
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

        account_id = sender_session.account_id
        if account_id not in self.rooms:
            return

        # v1.6 P0 多设备管理：更新设备活跃时间
        device_name = getattr(sender_session, "device_name", None)
        if device_name:
            self.mark_device_active(account_id, device_name)

        room = self.rooms[account_id]
        if sender_session.client_type == "mobile":
            desktop = room.get("desktop")
            if desktop and desktop.websocket:
                try:
                    await desktop.websocket.send_text(message_str)
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
                    sequenced_str = json.dumps(msg_obj)
                else:
                    sequenced_str = message_str
            except Exception:
                sequenced_str = message_str
            buf = room.get("msg_buffer")
            if buf is not None:
                buf.append(seq, sequenced_str)
            for m_session in list(room.get("mobiles", [])):
                try:
                    await m_session.websocket.send_text(sequenced_str)
                except Exception as e:
                    logger.error(f"Error routing desktop -> mobile in {account_id}: {e}")

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


manager = ConnectionManager()

# 后台心跳任务循环
async def heartbeat_background_task():
    while True:
        try:
            await asyncio.sleep(25)
            await manager.check_heartbeats()
        except Exception as e:
            logger.error(f"Error in heartbeat task: {e}")

@app.on_event("startup")
async def startup_event():
    asyncio.create_task(heartbeat_background_task())

@app.get("/")
def index():
    return {
        "status": "ok",
        "service": "OpenCode Secure Relay Server",
        "security": "Transport Layer Encryption (WSS/TLS) Supported",
        "version": "1.1.0"
    }

# 对外分发：ACRA 崩溃上报接收端（仅收脱敏字段，存本地文件）
CRASH_REPORT_DIR = os.getenv("RELAY_CRASH_REPORT_DIR", "crash_reports")

@app.post("/api/crash-report")
async def receive_crash_report(request: Request):
    """
    接收 Android 端 ACRA 上报的崩溃报告（JSON）。
    只保留脱敏字段，存入本地文件，不转发、不外传。
    """
    try:
        data = await request.json()
    except Exception:
        return JSONResponse(status_code=400, content={"ok": False, "error": "invalid json"})
    if not isinstance(data, dict):
        return JSONResponse(status_code=400, content={"ok": False, "error": "invalid payload"})
    # 脱敏白名单：只保留这些字段
    allowed = {
        "REPORT_ID", "APP_VERSION_CODE", "APP_VERSION_NAME", "PACKAGE_NAME",
        "ANDROID_VERSION", "PHONE_MODEL", "BRAND",
        "STACK_TRACE", "USER_APP_START_DATE", "USER_CRASH_DATE",
    }
    report = {k: data.get(k) for k in allowed if k in data}
    # 堆栈截断防爆（最多 64KB）
    if isinstance(report.get("STACK_TRACE"), str) and len(report["STACK_TRACE"]) > 65536:
        report["STACK_TRACE"] = report["STACK_TRACE"][:65536] + "\n...[truncated]"
    try:
        os.makedirs(CRASH_REPORT_DIR, exist_ok=True)
        ts = time.strftime("%Y%m%d-%H%M%S")
        rid = str(report.get("REPORT_ID", "unknown"))[:32]
        fname = os.path.join(CRASH_REPORT_DIR, f"crash-{ts}-{rid}.json")
        with open(fname, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        logger.warning(f"Crash report saved: {fname} "
                       f"(app {report.get('APP_VERSION_NAME')}, "
                       f"{report.get('PHONE_MODEL')}, Android {report.get('ANDROID_VERSION')})")
    except Exception as e:
        logger.error(f"Failed to save crash report: {e}")
        return JSONResponse(status_code=500, content={"ok": False, "error": "save failed"})
    return {"ok": True}

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

    # 2. 等待首条认证消息（10 秒超时）
    session: Optional[ClientSession] = None
    try:
        raw_auth_msg = await asyncio.wait_for(websocket.receive_text(), timeout=10.0)
        auth_data = json.loads(raw_auth_msg)
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
        ok, result = manager.claim_pairing(account_id or "", pairing_token, device_name)
        if ok:
            await websocket.send_text(json.dumps({
                "type": "pair_success",
                "account_id": result["account_id"],
                "desktop_name": result["desktop_name"],
                "device_secret": result["device_secret"],
                "message": "配对成功，请使用设备密钥重新连接。"
            }))
            # 通知桌面端有新设备配对
            room = manager.rooms.get(account_id or "")
            if room and room.get("desktop"):
                try:
                    await room["desktop"].websocket.send_text(json.dumps({
                        "type": "device_paired",
                        "device_name": device_name,
                        "message": f"新设备已配对：{device_name}"
                    }))
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
        "client_type": client_type
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
                "server_seq": manager.rooms.get(account_id, {}).get("next_seq", 1) - 1
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
                        session.account_id, parsed.get("desktop_name", "Desktop"))
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
                elif parsed.get("type") == "revoke_device":
                    ok = manager.revoke_device(session.account_id, parsed.get("device_name", ""))
                    await websocket.send_text(json.dumps({
                        "type": "device_revoked" if ok else "device_revoke_failed",
                        "device_name": parsed.get("device_name", "")
                    }))
                    continue
                elif parsed.get("type") == "rename_device" and session.client_type == "desktop":
                    ok = manager.rename_device(
                        session.account_id,
                        parsed.get("old_name", ""),
                        parsed.get("new_name", ""))
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
    host = os.getenv("HOST", "0.0.0.0")
    port = int(os.getenv("PORT", "8765"))
    ssl_cert = os.getenv("SSL_CERTFILE")
    ssl_key = os.getenv("SSL_KEYFILE")

    kwargs = {"host": host, "port": port, "workers": 1}
    logger.info("Ensuring single-worker operation to preserve in-memory room state.")
    if ssl_cert and ssl_key and os.path.exists(ssl_cert) and os.path.exists(ssl_key):
        logger.info(f"Starting WSS (TLS) server with cert: {ssl_cert}")
        kwargs["ssl_certfile"] = ssl_cert
        kwargs["ssl_keyfile"] = ssl_key
    else:
        logger.info("Starting unencrypted WS server (recommended to terminate TLS behind a reverse proxy in production).")

    uvicorn.run("server:app", **kwargs)
