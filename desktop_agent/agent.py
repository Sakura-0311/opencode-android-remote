import asyncio
import json
import logging
import os
import random
import secrets
import stat
import sys
from typing import Dict, Optional
import aiohttp
import websockets

from opencode_api import (
    DEFAULT_OPENCODE_BASE_URL,
    check_opencode_health,
    query_sessions,
    stream_opencode_response,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [DesktopAgent] %(message)s"
)
logger = logging.getLogger("DesktopAgent")

RELAY_SERVER_URL = os.getenv("RELAY_SERVER_URL", "ws://127.0.0.1:8765")
OPENCODE_API_URL = os.getenv("OPENCODE_API_URL", DEFAULT_OPENCODE_BASE_URL)
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
        # 创建受限权限文件 (0600)
        fd = os.open(SECRET_FILE_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(new_secret)
        logger.info(f"Initialized secure pairing secret in {SECRET_FILE_PATH} (mode 0600)")
    except Exception as e:
        logger.warning(f"Could not write secret to disk ({e}); using in-memory secret.")

    return new_secret

# ==============================================================================
# P2-9: 终端二维码配对信息渲染（不含 secret，仅含 account_id 与 relay_url）
# ==============================================================================
def print_pairing_banner(account_id: str, secret: str, relay_url: str):
    pairing_payload = json.dumps({
        "account_id": account_id,
        "relay_url": relay_url
    })

    print("\n" + "=" * 64)
    print("  🚀 OpenCode Desktop Bridge Agent (v1.1.0)")
    print("=" * 64)
    print(f"  🔑 Account ID (房间名):      \033[1;36m{account_id}\033[0m")
    # 按照 P0 安全规范：仅在终端启动时呈现一次给用户本人查阅输入，代码及日志绝不泄漏
    print(f"  🔒 Secret (访问鉴权密钥):    \033[1;32m{secret}\033[0m  (严禁泄露给未授权第三方)")
    print(f"  🌐 Relay Server URL:         {relay_url}")
    print(f"  🤖 Local OpenCode Target:     {OPENCODE_API_URL}")
    print("=" * 64)

    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(pairing_payload)
        print("  📱 手机扫码快速填入配对地址 (不含 Secret，Secret 需手动输入)：\n")
        qr.print_ascii(invert=True)
    except ImportError:
        print("  💡 提示: 安装 qrcode 库可在终端直接打印扫码二维码: pip install qrcode")
    
    print("=" * 64)
    print("  👉 在手机端 App 输入上述【账号】与【Secret】即可安全连接！\n")

# ==============================================================================
# P1-6: 任务跟踪管理（支持实时取消底层 HTTP SSE 流）
# ==============================================================================
class TaskManager:
    def __init__(self):
        # session_id -> asyncio.Task
        self.active_tasks: Dict[str, asyncio.Task] = {}

    def register(self, session_id: str, task: asyncio.Task):
        self.active_tasks[session_id] = task

    def cancel(self, session_id: str) -> bool:
        task = self.active_tasks.get(session_id)
        if task and not task.done():
            task.cancel()
            logger.info(f"Canceled execution task for session: {session_id}")
            return True
        return False

    def remove(self, session_id: str):
        self.active_tasks.pop(session_id, None)

task_manager = TaskManager()

# ==============================================================================
# 消息处理核心逻辑
# ==============================================================================
async def process_prompt_task(
    prompt_text: str,
    session_id: str,
    req_id: str,
    ws_relay: websockets.WebSocketClientProtocol,
    http_session: aiohttp.ClientSession
):
    try:
        # 1. 检查本地 OpenCode 服务是否存活
        is_healthy, version_info, err = await check_opencode_health(http_session, OPENCODE_API_URL)
        if not is_healthy:
            # P1-4: 坚决拒绝假模拟，直接显式返回标准错误结构
            logger.error(f"OpenCode health check failed: {err}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "OPENCODE_UNREACHABLE",
                "req_id": req_id,
                "session_id": session_id,
                "message": f"本地 opencode serve 未运行，请先在电脑端执行: opencode serve --port 4096 (详情: {err})"
            }))
            await ws_relay.send(json.dumps({
                "type": "stream_end",
                "req_id": req_id,
                "session_id": session_id
            }))
            return

        # 2. 正常流式调用本地 OpenCode
        async for chunk in stream_opencode_response(http_session, session_id, prompt_text, OPENCODE_API_URL):
            await ws_relay.send(json.dumps({
                "type": "stream_chunk",
                "req_id": req_id,
                "session_id": session_id,
                "chunk": chunk
            }))

        await ws_relay.send(json.dumps({
            "type": "stream_end",
            "req_id": req_id,
            "session_id": session_id
        }))

    except asyncio.CancelledError:
        # P1-6: 优雅处理取消异常，关闭流并回复手机端已中止
        logger.info(f"Prompt task for session {session_id} canceled successfully.")
        await ws_relay.send(json.dumps({
            "type": "cancelled",
            "req_id": req_id,
            "session_id": session_id
        }))
        raise
    except Exception as e:
        logger.error(f"Error during OpenCode streaming: {e}")
        await ws_relay.send(json.dumps({
            "type": "error",
            "code": "EXECUTION_ERROR",
            "req_id": req_id,
            "session_id": session_id,
            "message": f"执行发生异常: {str(e)}"
        }))
        await ws_relay.send(json.dumps({
            "type": "stream_end",
            "req_id": req_id,
            "session_id": session_id
        }))
    finally:
        task_manager.remove(session_id)


async def handle_mobile_message(
    msg_data: dict,
    ws_relay: websockets.WebSocketClientProtocol,
    http_session: aiohttp.ClientSession
):
    action = msg_data.get("action")
    session_id = msg_data.get("session_id", "default_session")
    payload = msg_data.get("payload", {})
    req_id = msg_data.get("req_id", "")

    logger.info(f"Received action: {action} (session_id={session_id}, req_id={req_id})")

    # P1-7: 应用层心跳处理
    if action == "ping" or msg_data.get("type") == "ping":
        await ws_relay.send(json.dumps({"type": "pong", "req_id": req_id}))
        return

    if action == "list_sessions":
        try:
            sessions_data = await query_sessions(http_session, OPENCODE_API_URL)
            await ws_relay.send(json.dumps({
                "type": "sessions_list",
                "req_id": req_id,
                "data": sessions_data
            }))
        except Exception as e:
            logger.warning(f"Could not fetch remote sessions ({e}), using default fallback session.")
            await ws_relay.send(json.dumps({
                "type": "sessions_list",
                "req_id": req_id,
                "data": [{"id": "default", "title": "Main Project Workspace"}]
            }))

    elif action == "send_prompt":
        prompt_text = payload.get("prompt", "")
        # 如果前序同一个 session 的任务还在运行，先取消它
        task_manager.cancel(session_id)

        # 通知手机端开始接收流
        await ws_relay.send(json.dumps({
            "type": "stream_start",
            "req_id": req_id,
            "session_id": session_id
        }))

        # P1-6: 包装为可取消的异步任务
        task = asyncio.create_task(
            process_prompt_task(prompt_text, session_id, req_id, ws_relay, http_session)
        )
        task_manager.register(session_id, task)

    elif action == "cancel":
        # P1-6: 真实执行任务中断
        was_canceled = task_manager.cancel(session_id)
        if not was_canceled:
            await ws_relay.send(json.dumps({
                "type": "cancelled",
                "req_id": req_id,
                "session_id": session_id
            }))


# ==============================================================================
# P0-1 & P2-8: 鉴权握手与指数退避重连守护
# ==============================================================================
async def run_desktop_agent(account_id: str, secret: str, relay_url: str):
    print_pairing_banner(account_id, secret, relay_url)

    # 规范化 WebSocket URL 路径 (兼容新版 /ws 和旧版路径)
    base_ws_url = relay_url.rstrip("/")
    if base_ws_url.endswith("/desktop"):
        ws_endpoint = base_ws_url
    else:
        ws_endpoint = f"{base_ws_url}/ws/{account_id}/desktop"

    # P2-8: 退避重连配置
    backoff = 3.0
    max_backoff = 60.0
    backoff_factor = 1.5

    async with aiohttp.ClientSession() as http_session:
        # P1-5: 启动自检
        is_healthy, version, err = await check_opencode_health(http_session, OPENCODE_API_URL)
        if is_healthy:
            logger.info(f"✔ OpenCode API 自检通过：检测到服务就绪 (版本/状态: {version})")
        else:
            logger.warning(f"⚠ OpenCode API 自检提醒：{err}")
            logger.warning("  请确保已执行 `opencode serve --port 4096`，否则手机发来的指令将返回报错。")

        while True:
            try:
                logger.info(f"Connecting to Relay Server: {ws_endpoint}")
                async with websockets.connect(
                    ws_endpoint,
                    ping_interval=20,
                    ping_timeout=10,
                    close_timeout=5
                ) as ws:
                    # P0-1: 建立连接后第一条消息必须是 auth 消息
                    auth_message = {
                        "type": "auth",
                        "account_id": account_id,
                        "secret": secret,
                        "client_type": "desktop"
                    }
                    await ws.send(json.dumps(auth_message))

                    # 等待认证结果
                    auth_resp_raw = await asyncio.wait_for(ws.recv(), timeout=10.0)
                    auth_resp = json.loads(auth_resp_raw)

                    if auth_resp.get("type") == "auth_error":
                        logger.error(f"Relay authentication rejected: {auth_resp.get('message')}")
                        logger.error("请检查 relay_server 上的房间密钥是否冲突。将在 10 秒后重试...")
                        await asyncio.sleep(10)
                        continue
                    elif auth_resp.get("type") != "auth_ok":
                        logger.warning(f"Unexpected initial response from relay: {auth_resp_raw}")

                    logger.info(f"✔ 鉴权通过！成功注册进入中继房间 [{account_id}]。等待手机端下发指令...")
                    # 成功连接，重置退避计时
                    backoff = 3.0

                    # 循环接收并路由指令
                    async for message in ws:
                        try:
                            data = json.loads(message)
                            # P1-7: 处理心跳 ping
                            if data.get("type") == "ping":
                                await ws.send(json.dumps({"type": "pong", "timestamp": data.get("timestamp")}))
                                continue
                            elif data.get("type") == "pong":
                                continue

                            await handle_mobile_message(data, ws, http_session)
                        except json.JSONDecodeError:
                            logger.warning(f"Received non-json message: {message}")

            except (websockets.ConnectionClosed, OSError, Exception) as e:
                # P2-8: 异常捕获并进行指数退避 + 随机抖动
                jitter = random.uniform(0.85, 1.15)
                sleep_duration = min(max_backoff, backoff * jitter)
                logger.warning(f"Connection error ({e}). Reconnecting in {sleep_duration:.1f}s...")
                await asyncio.sleep(sleep_duration)
                backoff = min(max_backoff, backoff * backoff_factor)


if __name__ == "__main__":
    account = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ACCOUNT_ID
    relay = sys.argv[2] if len(sys.argv) > 2 else RELAY_SERVER_URL
    secret_val = get_or_create_secret()

    try:
        asyncio.run(run_desktop_agent(account, secret_val, relay))
    except KeyboardInterrupt:
        print("\nStopping desktop agent...")
