import asyncio
import json
import logging
import os
import random
import secrets
import stat
import sys
import time
from typing import Dict, Optional, Any
import aiohttp
import websockets

from opencode_api import (
    DEFAULT_OPENCODE_BASE_URL,
    check_opencode_health,
    query_sessions,
    create_session,
    abort_session,
    respond_to_permission,
    send_session_message_async,
    subscribe_events_stream,
    get_agents,
    get_providers,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [DesktopAgent] %(message)s"
)
logger = logging.getLogger("DesktopAgent")

RELAY_SERVER_URL = os.getenv("RELAY_SERVER_URL", "ws://127.0.0.1:8765")
RELAY_ADMIN_TOKEN = os.getenv("RELAY_ADMIN_TOKEN", "")  # B-8: 建房管理令牌（relay 侧配置了才需要）
OPENCODE_API_URL = os.getenv("OPENCODE_API_URL", DEFAULT_OPENCODE_BASE_URL)
OPENCODE_PASSWORD = os.getenv("OPENCODE_SERVER_PASSWORD", None)
DEFAULT_ACCOUNT_ID = os.getenv("OPENCODE_ACCOUNT_ID", "user_dev_001")
SECRET_FILE_PATH = os.getenv("OPENCODE_SECRET_FILE", ".opencode_secret")

# ==============================================================================
# P0-1: 本地密钥管理（0600 受限权限，内存保管，绝不打日志）
# ==============================================================================
def get_or_create_secret() -> str:
    """获取或初始化持久化配对 Secret，确保权限仅当前用户可读写 (0600)"""
    if os.path.exists(SECRET_FILE_PATH):
        try:
            with open(SECRET_FILE_PATH, "r", encoding="utf-8") as f:
                secret = f.read().strip()
                if secret and len(secret) >= 16:
                    return secret
        except Exception as e:
            logger.warning(f"Failed to read existing secret file: {e}")

    # 生成 32 字节高熵随机十六进制密钥
    new_secret = secrets.token_hex(16)
    try:
        fd = os.open(SECRET_FILE_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(new_secret)
        logger.info(f"Initialized secure pairing secret in {SECRET_FILE_PATH} (mode 0600)")
    except Exception as e:
        logger.warning(f"Could not write secret to disk ({e}); using in-memory secret.")

    return new_secret

# ==============================================================================
# P2-9: 终端配对信息渲染
# ==============================================================================
def print_pairing_banner(account_id: str, secret: str, relay_url: str):
    pairing_payload = json.dumps({
        "account_id": account_id,
        "relay_url": relay_url
    })

    print("\n" + "=" * 64)
    print("  🚀 OpenCode Desktop Bridge Agent (v1.4 - Real Protocol Edition)")
    print("=" * 64)
    print(f"  🔑 Account ID (房间名):      \033[1;36m{account_id}\033[0m")
    print(f"  🔒 Secret (访问鉴权密钥):    \033[1;32m{secret}\033[0m  (严禁泄露给未授权第三方)")
    print(f"  🌐 Relay Server URL:         {relay_url}")
    print(f"  🤖 Local OpenCode Target:     {OPENCODE_API_URL}")
    if OPENCODE_PASSWORD:
        print(f"  🔐 OpenCode Basic Auth:      已启用密码认证保护")
    print("=" * 64)

    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(pairing_payload)
        print("  📱 手机扫码快速填入配对地址 (不含 Secret，Secret 可使用 App 一键粘贴)：\n")
        qr.print_ascii(invert=True)
    except ImportError:
        print("  💡 提示: 安装 qrcode 库可在终端直接打印扫码二维码: pip install qrcode")
    
    print("=" * 64)
    print("  👉 在手机端 App 输入上述【账号】并在 Secret 栏点击粘贴即可安全连接！\n")

# ==============================================================================
# 任务跟踪管理
# ==============================================================================
class TaskManager:
    def __init__(self):
        self.active_tasks: Dict[str, asyncio.Task] = {}

    def register(self, session_id: str, task: asyncio.Task):
        self.active_tasks[session_id] = task

    def cancel(self, session_id: str) -> bool:
        task = self.active_tasks.get(session_id)
        if task and not task.done():
            task.cancel()
            logger.info(f"Canceled local task for session: {session_id}")
            return True
        return False

    def remove(self, session_id: str):
        self.active_tasks.pop(session_id, None)

task_manager = TaskManager()

class ToolApprovalManager:
    def __init__(self, timeout_seconds: float = 120.0):
        self.timeout_seconds = timeout_seconds
        self._pending: Dict[str, Dict[str, Any]] = {}

    def create_approval(self, session_id: str, permission_id: str, tool_name: str, payload: Any) -> Dict[str, Any]:
        self.clean_expired()
        nonce = secrets.token_hex(16)
        record = {
            "session_id": session_id,
            "permission_id": permission_id,
            "tool_name": tool_name,
            "payload": payload,
            "nonce": nonce,
            "expires_at": time.time() + self.timeout_seconds
        }
        self._pending[permission_id] = record
        return record

    def consume_approval(self, permission_id: str, nonce: str) -> Optional[Dict[str, Any]]:
        self.clean_expired()
        record = self._pending.get(permission_id)
        if not record:
            return None
        if not secrets.compare_digest(record["nonce"], nonce):
            return None
        del self._pending[permission_id]
        return record

    def validate_approval(self, permission_id: str, nonce: str) -> Optional[Dict[str, Any]]:
        """B-7: 只校验 nonce，不删除；调用成功后再删，失败可重试"""
        self.clean_expired()
        record = self._pending.get(permission_id)
        if not record:
            return None
        if not secrets.compare_digest(record["nonce"], nonce):
            return None
        return record

    def remove_approval(self, permission_id: str):
        """B-7: 审批成功送达后删除记录"""
        self._pending.pop(permission_id, None)

    def clean_expired(self):
        now = time.time()
        for pid in [p for p, i in self._pending.items() if now > i["expires_at"]]:
            del self._pending[pid]

tool_guard = ToolApprovalManager(timeout_seconds=120.0)

# ==============================================================================
# B-2/B-3: SSE 事件解析辅助 — 优先读 properties，拒绝无归属事件
# 真实 OpenCode SSE 事件形如：
#   data: {"type":"message.part.delta","properties":{"sessionID":"ses_xxx",...,"delta":"..."}}
# 旧代码读顶层字段，永远拿到空值；B-3 要求不匹配手机端已知会话的事件直接丢弃
# ==============================================================================
known_session_ids: set = set()

def _extract_event_session(event: Dict[str, Any]) -> Optional[str]:
    """B-2: 优先从 properties 取会话 ID，顶层字段仅作兼容分支"""
    props = event.get("properties") or {}
    if isinstance(props, dict):
        for key in ("sessionID", "sessionId", "session_id"):
            sid = props.get(key)
            if sid:
                return sid
    for key in ("session_id", "sessionId", "sessionID"):
        sid = event.get(key)
        if sid:
            return sid
    return None

def _extract_event_delta(event: Dict[str, Any]) -> str:
    """B-2: 优先从 properties 取增量文本，顶层字段仅作兼容分支"""
    props = event.get("properties") or {}
    if isinstance(props, dict):
        for key in ("delta", "text", "content"):
            val = props.get(key)
            if val:
                return val
    for key in ("delta", "text", "content"):
        val = event.get(key)
        if val:
            return val
    return ""


# ==============================================================================
# B-10: SSE 游标持久化（断线重连断点续传）
# ==============================================================================
SSE_CURSOR_FILE = os.getenv("OPENCODE_SSE_CURSOR_FILE", ".opencode_sse_cursor")

def load_sse_cursor() -> Optional[str]:
    try:
        with open(SSE_CURSOR_FILE, "r", encoding="utf-8") as f:
            return f.read().strip() or None
    except Exception:
        return None

def save_sse_cursor(event_id: str):
    try:
        with open(SSE_CURSOR_FILE, "w", encoding="utf-8") as f:
            f.write(event_id)
    except Exception as e:
        logger.warning(f"Failed to persist SSE cursor: {e}")


# ==============================================================================
# OpenCode GET /event SSE 实时事件监听与透传
# ==============================================================================
async def listen_opencode_events_stream(
    ws_relay: websockets.WebSocketClientProtocol,
    http_session: aiohttp.ClientSession
):
    """
    持久订阅 OpenCode 官方 GET /event SSE 流，将增量 Chunk 与工具审批事件转发至手机端
    B-10: 游标持久化，断线重连时携带 Last-Event-ID 续传
    """
    logger.info("Starting OpenCode SSE event listener on GET /event...")
    cursor_sink: Dict[str, str] = {}
    last_id = load_sse_cursor()
    if last_id:
        logger.info(f"B-10: Resuming SSE stream from last-event-id: {last_id}")
    while True:
        try:
            async for event in subscribe_events_stream(
                http_session, OPENCODE_API_URL, OPENCODE_PASSWORD,
                last_event_id=last_id, event_id_sink=cursor_sink
            ):
                # B-10: 收到新游标即持久化
                new_id = cursor_sink.get("last_event_id")
                if new_id and new_id != last_id:
                    last_id = new_id
                    save_sse_cursor(new_id)
                event_type = event.get("type", "")
                # B-2: 会话 ID 优先从 properties 取；B-3: 无归属或非已知会话的事件直接丢弃，防串台
                session_id = _extract_event_session(event)
                if not session_id or (known_session_ids and session_id not in known_session_ids):
                    continue

                # 1. 增量 Token 输出 (message.part.delta)
                if event_type in ("message.part.delta", "delta", "stream_chunk"):
                    delta_text = _extract_event_delta(event)
                    if delta_text:
                        await ws_relay.send(json.dumps({
                            "type": "stream_chunk",
                            "session_id": session_id,
                            "chunk": delta_text
                        }))

                # 2. 工具权限请求 (permission.asked / tool_approval)
                elif event_type in ("permission.asked", "permission.request", "permission"):
                    permission_id = event.get("id", event.get("permission_id", secrets.token_hex(8)))
                    tool_info = event.get("tool", {})
                    tool_name = tool_info.get("name", event.get("tool_name", "sensitive_tool"))
                    file_path = event.get("file_path", tool_info.get("path"))
                    summary = event.get("summary", tool_info.get("description", "申请执行本地文件修改或终端命令"))
                    raw_diff = event.get("diff", event.get("raw_content", ""))

                    diff_lines = []
                    if raw_diff:
                        for line in raw_diff.split("\n"):
                            if line.startswith("+"):
                                diff_lines.append({"type": "ADDED", "content": line[1:]})
                            elif line.startswith("-"):
                                diff_lines.append({"type": "REMOVED", "content": line[1:]})
                            elif line.startswith("@"):
                                diff_lines.append({"type": "HEADER", "content": line})
                            else:
                                diff_lines.append({"type": "UNCHANGED", "content": line})

                    rec = tool_guard.create_approval(session_id, permission_id, tool_name, event)
                    logger.info(f"Forwarding tool approval request with Nonce to mobile: {tool_name} (call_id={permission_id})")
                    await ws_relay.send(json.dumps({
                        "type": "tool_approval_request",
                        "session_id": session_id,
                        "call_id": permission_id,
                        "tool_name": tool_name,
                        "file_path": file_path,
                        "summary": summary,
                        "diff_lines": diff_lines,
                        "raw_content": raw_diff,
                        "nonce": rec["nonce"],
                        "expires_at": rec["expires_at"]
                    }))

                # 3. 会话空闲或执行完成 (session.idle / message.complete)
                elif event_type in ("session.idle", "message.complete", "stream.end"):
                    await ws_relay.send(json.dumps({
                        "type": "stream_end",
                        "session_id": session_id
                    }))

        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.exception(f"SSE /event connection interrupted ({e}). Reconnecting in 3s...")
            await asyncio.sleep(3.0)

# ==============================================================================
# 手机端消息调度与处理
# ==============================================================================
async def handle_mobile_message(
    msg_data: dict,
    ws_relay: websockets.WebSocketClientProtocol,
    http_session: aiohttp.ClientSession
):
    action = msg_data.get("action") or msg_data.get("type")
    session_id = msg_data.get("session_id", "default")
    payload = msg_data.get("payload", {})
    req_id = msg_data.get("req_id", "")

    # 1. 心跳响应
    if action == "ping":
        await ws_relay.send(json.dumps({"type": "pong", "req_id": req_id}))
        return

    # 2. 查询真实会话列表 (GET /session)
    if action == "list_sessions":
        try:
            sessions_data = await query_sessions(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
            # B-3: 登记手机端可见的真实会话，供 SSE 过滤用
            for s in sessions_data or []:
                sid = s.get("id") if isinstance(s, dict) else None
                if sid:
                    known_session_ids.add(sid)
            await ws_relay.send(json.dumps({
                "type": "sessions_list",
                "req_id": req_id,
                "data": sessions_data
            }))
        except Exception as e:
            logger.error(f"Error querying real sessions: {e}")
            await ws_relay.send(json.dumps({
                "type": "sessions_list",
                "req_id": req_id,
                "data": []
            }))

    # v1.6 P1: 获取 Model/Agent 配置（动态，非硬编码）
    elif action == "get_config":
        try:
            agents = await get_agents(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
            providers = await get_providers(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
            await ws_relay.send(json.dumps({
                "type": "config_data",
                "req_id": req_id,
                "agents": agents,
                "providers": providers,
            }))
            logger.info(f"v1.6: sent config to mobile ({len(agents)} agents)")
        except Exception as e:
            logger.error(f"Error fetching config: {e}")
            await ws_relay.send(json.dumps({
                "type": "config_data",
                "req_id": req_id,
                "agents": [],
                "providers": {},
                "error": str(e),
            }))

    # 3. 创建真实会话 (POST /session)
    elif action == "create_session":
        title = payload.get("title", "Mobile Task")
        try:
            new_session = await create_session(http_session, title, OPENCODE_API_URL, OPENCODE_PASSWORD)
            # B-3: 新建会话同样登记
            if isinstance(new_session, dict) and new_session.get("id"):
                known_session_ids.add(new_session["id"])
            await ws_relay.send(json.dumps({
                "type": "session_created",
                "req_id": req_id,
                "session": new_session
            }))
        except Exception as e:
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "CREATE_SESSION_FAILED",
                "message": f"创建会话失败: {e}"
            }))

    # 4. 发送提示词 (POST /session/:id/message)
    elif action == "send_prompt":
        prompt_text = payload.get("prompt", "")
        # v1.6 P1: 移动端可指定 model {providerID, modelID} 与 agent
        req_model = payload.get("model")
        req_agent = payload.get("agent")
        # 自检本地 OpenCode 服务
        is_healthy, version, err = await check_opencode_health(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
        if not is_healthy:
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "OPENCODE_UNREACHABLE",
                "req_id": req_id,
                "session_id": session_id,
                "message": f"本地 opencode serve 未运行或端口未开放 (详情: {err})，请在电脑终端执行: opencode serve --port 4096"
            }))
            await ws_relay.send(json.dumps({
                "type": "stream_end",
                "req_id": req_id,
                "session_id": session_id
            }))
            return

        # 若 session_id 是未初始化的默认占位，自动拉取或创建真实会话
        target_session_id = session_id
        if target_session_id in ("default", ""):
            existing = await query_sessions(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
            if existing:
                target_session_id = existing[0].get("id", "default")
            else:
                created = await create_session(http_session, "Mobile Workspace", OPENCODE_API_URL, OPENCODE_PASSWORD)
                target_session_id = created.get("id", "default")
        # B-3: 目标会话登记为已知
        if target_session_id not in ("default", ""):
            known_session_ids.add(target_session_id)

        # 通知手机端流开始
        await ws_relay.send(json.dumps({
            "type": "stream_start",
            "req_id": req_id,
            "session_id": target_session_id
        }))

        try:
            logger.info(f"Posting message to session {target_session_id}...")
            await send_session_message_async(
                http_session,
                target_session_id,
                prompt_text,
                OPENCODE_API_URL,
                OPENCODE_PASSWORD,
                model=req_model if isinstance(req_model, dict) else None,
                agent=req_agent if isinstance(req_agent, str) else None,
            )
        except Exception as e:
            logger.error(f"Error sending message to OpenCode: {e}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "EXECUTION_ERROR",
                "session_id": target_session_id,
                "message": f"发送指令失败: {e}"
            }))
            await ws_relay.send(json.dumps({
                "type": "stream_end",
                "session_id": target_session_id
            }))

    # 5. 中断/取消会话执行 (POST /session/:id/abort)
    elif action == "cancel":
        logger.info(f"Cancelling execution for session: {session_id}")
        await abort_session(http_session, session_id, OPENCODE_API_URL, OPENCODE_PASSWORD)
        await ws_relay.send(json.dumps({
            "type": "cancelled",
            "req_id": req_id,
            "session_id": session_id
        }))

    # 6. 处理工具审批回传 (POST /session/:id/permissions/:permID)
    elif action == "tool_approval_response":
        call_id = payload.get("call_id") or msg_data.get("call_id")
        nonce = payload.get("nonce", "")
        is_approved = payload.get("approved", True)
        reason = payload.get("reason", "")
        logger.info(f"Handling approval decision for permission {call_id}: approved={is_approved}")
        if not call_id:
            return
        # B-5: 空 nonce 直接拒绝，不再放行（防重放守卫）
        if not nonce:
            logger.warning(f"Rejected tool approval response for {call_id}: missing nonce (SEC-04 guard)")
            return
        record = tool_guard.validate_approval(call_id, nonce)
        if not record:
            logger.warning(f"Rejected tool approval response for {call_id}: Nonce invalid or expired (SEC-04 guard)")
            return
        # B-7: 用记录里的 session_id（而非信封里的），调用成功后再删记录以便重试
        ok = await respond_to_permission(
            http_session,
            record["session_id"],
            call_id,
            is_approved,
            reason,
            OPENCODE_API_URL,
            OPENCODE_PASSWORD
        )
        if ok:
            tool_guard.remove_approval(call_id)
        else:
            logger.warning(f"respond_to_permission failed for {call_id}, keeping record for retry")

# ==============================================================================
# Agent 主运行循环与自动重连
# ==============================================================================
async def run_desktop_agent(account_id: str, secret: str, relay_url: str):
    print_pairing_banner(account_id, secret, relay_url)

    base_ws_url = relay_url.rstrip("/")
    if base_ws_url.endswith("/desktop"):
        ws_endpoint = base_ws_url
    else:
        ws_endpoint = f"{base_ws_url}/ws/{account_id}/desktop"

    backoff = 3.0
    max_backoff = 60.0
    backoff_factor = 1.5

    async with aiohttp.ClientSession() as http_session:
        # 启动自检
        is_healthy, version, err = await check_opencode_health(http_session, OPENCODE_API_URL, OPENCODE_PASSWORD)
        if is_healthy:
            logger.info(f"✔ OpenCode API 真实契约自检通过: 服务就绪 (状态: {version})")
        else:
            logger.warning(f"⚠ OpenCode API 自检警示: {err}")
            logger.warning("  请确保已启动真实服务: opencode serve --port 4096")

        while True:
            try:
                logger.info(f"Connecting to Relay Server: {ws_endpoint}")
                async with websockets.connect(
                    ws_endpoint,
                    ping_interval=20,
                    ping_timeout=10,
                    close_timeout=5
                ) as ws:
                    auth_message = {
                        "type": "auth",
                        "account_id": account_id,
                        "secret": secret,
                        "client_type": "desktop"
                    }
                    # B-8: 若配置了建房管理令牌则一并上报
                    if RELAY_ADMIN_TOKEN:
                        auth_message["admin_token"] = RELAY_ADMIN_TOKEN
                    await ws.send(json.dumps(auth_message))

                    auth_resp_raw = await asyncio.wait_for(ws.recv(), timeout=10.0)
                    auth_resp = json.loads(auth_resp_raw)

                    if auth_resp.get("type") == "auth_error":
                        logger.error(f"Relay authentication rejected: {auth_resp.get('message')}")
                        await asyncio.sleep(10)
                        continue

                    logger.info("✔ Successfully authenticated with Relay Server! Desktop bridge is active.")
                    backoff = 3.0

                    # 启动后台 OpenCode GET /event SSE 监听协程
                    event_listener_task = asyncio.create_task(
                        listen_opencode_events_stream(ws, http_session)
                    )

                    try:
                        async for raw_msg in ws:
                            try:
                                msg_data = json.loads(raw_msg)
                                await handle_mobile_message(msg_data, ws, http_session)
                            except json.JSONDecodeError:
                                logger.error(f"Received malformed JSON from relay: {raw_msg}")
                            except Exception as e:
                                logger.error(f"Error handling mobile message: {e}")
                    finally:
                        event_listener_task.cancel()

            except (websockets.exceptions.ConnectionClosedError,
                    websockets.exceptions.ConnectionClosedOK,
                    ConnectionRefusedError,
                    OSError) as e:
                jitter = random.uniform(0.85, 1.15)
                actual_backoff = min(max_backoff, backoff * jitter)
                logger.warning(f"Connection lost ({e}). Reconnecting in {actual_backoff:.1f}s...")
                await asyncio.sleep(actual_backoff)
                backoff = min(max_backoff, backoff * backoff_factor)
            except Exception as e:
                logger.error(f"Unexpected desktop agent error: {e}. Retrying in 5s...")
                await asyncio.sleep(5.0)

async def run_pairing_flow(account_id: str, secret: str, relay_url: str):
    """
    v1.6 P0 一键扫码配对：
    向 Relay 申请一次性配对码，在终端显示二维码，手机扫码后完成设备授权。
    二维码中不包含长期 Secret，仅含短期一次性 pairing_token。
    """
    import socket
    desktop_name = socket.gethostname()

    base_ws_url = relay_url.rstrip("/")
    ws_endpoint = base_ws_url if base_ws_url.endswith("/desktop") else f"{base_ws_url}/ws/{account_id}/desktop"

    print("\n" + "=" * 60)
    print("  OpenCode Remote v1.6 — 一键扫码配对")
    print("=" * 60)

    try:
        async with websockets.connect(ws_endpoint, ping_interval=20, ping_timeout=10) as ws:
            auth_message = {"type": "auth", "account_id": account_id,
                            "secret": secret, "client_type": "desktop"}
            if RELAY_ADMIN_TOKEN:
                auth_message["admin_token"] = RELAY_ADMIN_TOKEN
            await ws.send(json.dumps(auth_message))
            auth_resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=10.0))
            if auth_resp.get("type") == "auth_error":
                print(f"  ✘ Relay 认证失败: {auth_resp.get('message')}")
                return

            # 申请一次性配对码
            await ws.send(json.dumps({
                "type": "create_pairing",
                "desktop_name": desktop_name,
            }))
            resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=10.0))
            if resp.get("type") != "pairing_created":
                print(f"  ✘ 配对码申请失败: {resp.get('error', resp)}")
                return

            token = resp["pairing_token"]
            ttl = resp.get("ttl_seconds", 120)
            # 二维码内容：仅含中继地址、房间号、一次性 token，不含长期 Secret
            import urllib.parse
            qr_payload = (
                "opencode-remote://pair?"
                + urllib.parse.urlencode({
                    "relay": relay_url,
                    "account": account_id,
                    "token": token,
                    "name": desktop_name,
                })
            )
            print(f"\n  电脑: {desktop_name}   有效期: {ttl} 秒（一次性）\n")
            _print_qr(qr_payload)
            print(f"\n  配对链接（也可手动输入）:\n  {qr_payload}\n")
            print("  请在手机 App 中扫描上方二维码，等待配对确认…\n")

            # 等待配对完成通知
            try:
                while True:
                    msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=float(ttl + 10)))
                    if msg.get("type") == "device_paired":
                        print(f"\n  ✔ 配对成功！新设备：{msg.get('device_name')}")
                        print("  该设备已获得独立密钥，可随时在桌面端撤销。")
                        break
            except asyncio.TimeoutError:
                print("\n  ✘ 配对码已过期，请重新运行配对。")
    except Exception as e:
        print(f"  ✘ 配对失败: {e}")


def _print_qr(payload: str):
    """终端显示二维码；未安装 qrcode 库时降级为纯文本提示。"""
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(payload)
        qr.make()
        # 反色块绘制，终端可扫
        matrix = qr.get_matrix()
        for row in matrix:
            print("  " + "".join("██" if c else "  " for c in row))
    except ImportError:
        print("  [提示] 安装 qrcode 库可在终端直接显示二维码：pip install qrcode")
        print("  当前请复制上方配对链接到手机，或在 App 中手动输入配对码。")


def main():
    # v1.6: pair 子命令用于一键扫码配对
    if len(sys.argv) > 1 and sys.argv[1] == "pair":
        account_id = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_ACCOUNT_ID
        relay_url = sys.argv[3] if len(sys.argv) > 3 else RELAY_SERVER_URL
        secret = get_or_create_secret()
        try:
            asyncio.run(run_pairing_flow(account_id, secret, relay_url))
        except KeyboardInterrupt:
            print("\n配对已取消。")
        return

    account_id = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ACCOUNT_ID
    relay_url = sys.argv[2] if len(sys.argv) > 2 else RELAY_SERVER_URL
    secret = get_or_create_secret()

    try:
        asyncio.run(run_desktop_agent(account_id, secret, relay_url))
    except KeyboardInterrupt:
        print("\n[OpenCode Desktop Bridge] Terminated gracefully by user.")

if __name__ == "__main__":
    main()
