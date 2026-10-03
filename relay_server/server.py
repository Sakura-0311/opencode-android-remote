import hmac
import asyncio
import hashlib
import json
import logging
import os
import sys
import time
from typing import Dict, Set, Optional, Tuple
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
import uvicorn

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [OpenCodeRelay] %(message)s"
)
logger = logging.getLogger("OpenCodeRelay")

app = FastAPI(title="OpenCode Cloud Relay Server", version="1.1.0")

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


class ConnectionManager:
    def __init__(self):
        # account_id -> {
        #   "secret_hash": str,
        #   "desktop": Optional[ClientSession],
        #   "mobiles": Set[ClientSession]
        # }
        self.rooms: Dict[str, dict] = {}
        # websocket -> ClientSession
        self.sessions: Dict[WebSocket, ClientSession] = {}

    def get_session(self, ws: WebSocket) -> Optional[ClientSession]:
        return self.sessions.get(ws)

    def register_authenticated(self, session: ClientSession, secret: str) -> Tuple[bool, str]:
        """
        认证并注册进入指定房间。
        - Desktop 注册：初始化房间 secret_hash 或校验现有 secret_hash；
        - Mobile 注册：必须核对已存 room 的 secret_hash。
        """
        account_id = session.account_id
        secret_hash = hashlib.sha256(secret.encode("utf-8")).hexdigest()

        if account_id not in self.rooms:
            # 只有 Desktop 才能首次创建并绑定房间密码
            if session.client_type != "desktop":
                return False, "Room does not exist yet. Please start desktop agent first."
            self.rooms[account_id] = {
                "secret_hash": secret_hash,
                "desktop": None,
                "mobiles": set()
            }
            logger.info(f"New room created: {account_id} by desktop.")

        room = self.rooms[account_id]

        # 密码比对
        if not hmac.compare_digest(room["secret_hash"], secret_hash):
            return False, "Invalid secret for account_id."

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
            for m_session in list(room.get("mobiles", [])):
                try:
                    await m_session.websocket.send_text(message_str)
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
    success, reason = manager.register_authenticated(session, secret)
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
