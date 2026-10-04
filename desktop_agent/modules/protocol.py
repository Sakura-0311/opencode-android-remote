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
    get_projects,
    get_current_project,
    get_vcs_info,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [DesktopAgent] %(message)s"
)
logger = logging.getLogger("DesktopAgent")
from modules import config

from modules.secrets import get_or_create_secret, print_pairing_banner
from modules.fileops import (
    _resolve_sandboxed_path, _list_dir_entries, _read_text_file, PathNotAllowedError,
)
from modules.state import (
    tool_guard, known_session_ids, listen_opencode_events_stream,
    _extract_event_session, _extract_event_delta,
)
async def handle_mobile_message(
    msg_data: dict,
    ws_relay: websockets.WebSocketClientProtocol,
    http_session: aiohttp.ClientSession
):
    action = msg_data.get("action") or msg_data.get("type")
    session_id = msg_data.get("session_id", "default")
    payload = msg_data.get("payload", {})
    req_id = msg_data.get("req_id", "")
    client_msg_id = msg_data.get("client_msg_id", "")

    # v2.3: 写操作幂等——重复的 send_prompt/cancel 直接回 ack，不重新执行
    if action in ("send_prompt", "cancel") and client_msg_id:
        if _is_duplicate_client_msg(client_msg_id):
            logger.info(f"[v2.3] duplicate {action} ignored")
            await ws_relay.send(json.dumps({
                "type": "duplicate_ignored",
                "req_id": req_id,
                "client_msg_id": client_msg_id,
                "action": action,
            }))
            return

    # 1. 心跳响应
    if action == "ping":
        await ws_relay.send(json.dumps({"type": "pong", "req_id": req_id}))
        return

    # 2. 查询真实会话列表 (GET /session)
    if action == "list_sessions":
        try:
            sessions_data = await query_sessions(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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
            agents, agents_err = await get_agents(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            providers, providers_err = await get_providers(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            await ws_relay.send(json.dumps({
                "type": "config_data",
                "req_id": req_id,
                "agents": agents,
                "providers": providers,
                # N-6: 取不到时带上错误，App 侧显示错误而非空白列表
                "agents_error": agents_err,
                "providers_error": providers_err,
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

    # v1.6 P1: 项目管理中心——获取项目列表（含 Git 分支、工作区状态）
    elif action == "get_projects":
        try:
            projects, projects_err = await get_projects(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            current, current_err = await get_current_project(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            vcs, vcs_err = await get_vcs_info(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            await ws_relay.send(json.dumps({
                "type": "projects_data",
                "req_id": req_id,
                "projects": projects,
                "current": current,
                "vcs": vcs,
                # N-6: 取不到时带上错误，App 侧显示错误而非空白列表
                "projects_error": projects_err,
                "vcs_error": vcs_err or current_err,
            }))
            logger.info(f"v1.6: sent {len(projects)} projects to mobile")
        except Exception as e:
            logger.error(f"Error fetching projects: {e}")
            await ws_relay.send(json.dumps({
                "type": "projects_data",
                "req_id": req_id,
                "projects": [],
                "current": {},
                "vcs": {},
                "error": str(e),
            }))

    # 3. 创建真实会话 (POST /session)
    elif action == "create_session":
        title = payload.get("title", "Mobile Task")
        try:
            new_session = await create_session(http_session, title, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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
        is_healthy, version, err = await check_opencode_health(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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
            existing = await query_sessions(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
            if existing:
                target_session_id = existing[0].get("id", "default")
            else:
                created = await create_session(http_session, "Mobile Workspace", config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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

        # P1-9: TaskManager 接入任务生命周期——发送任务注册为可取消的 asyncio.Task
        prompt_task = asyncio.create_task(send_session_message_async(
            http_session,
            target_session_id,
            prompt_text,
            config.OPENCODE_API_URL,
            config.OPENCODE_PASSWORD,
            model=req_model if isinstance(req_model, dict) else None,
            agent=req_agent if isinstance(req_agent, str) else None,
        ))
        task_manager.register(target_session_id, prompt_task)
        try:
            logger.info(f"Posting message to session {target_session_id}...")
            await prompt_task
        except asyncio.CancelledError:
            logger.info(f"Prompt send task cancelled for session {target_session_id}")
            await ws_relay.send(json.dumps({
                "type": "cancelled",
                "req_id": req_id,
                "session_id": target_session_id
            }))
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
        finally:
            # P1-9: 完成/异常/取消均移除注册，避免泄漏
            task_manager.remove(target_session_id)

    # 5. 中断/取消会话执行 (POST /session/:id/abort)
    elif action == "cancel":
        logger.info(f"Cancelling execution for session: {session_id}")
        # P1-9: 先取消本地尚未完成的发送任务，再调服务端 abort
        task_manager.cancel(session_id)
        await abort_session(http_session, session_id, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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
            config.OPENCODE_API_URL,
            config.OPENCODE_PASSWORD
        )
        if ok:
            tool_guard.remove_approval(call_id)
        else:
            logger.warning(f"respond_to_permission failed for {call_id}, keeping record for retry")

    # P2-12: 文件浏览器——列目录（v2.2.1-A 沙盒）
    elif action == "file_list":
        req_path = payload.get("path") or _get_file_roots()[0]
        try:
            entries = _list_dir_entries(req_path)
            await ws_relay.send(json.dumps({
                "type": "file_list_result",
                "req_id": req_id,
                "path": os.path.realpath(os.path.abspath(os.path.expanduser(req_path))),
                "entries": entries,
                "roots": _get_file_roots(),
            }))
        except PathNotAllowedError as e:
            logger.warning(f"file_list blocked (sandbox): {req_path}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "PATH_NOT_ALLOWED",
                "req_id": req_id,
                "message": str(e)
            }))
        except Exception as e:
            logger.warning(f"file_list failed for {req_path}: {e}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "FILE_LIST_ERROR",
                "req_id": req_id,
                "message": f"读取目录失败: {e}"
            }))

    # P2-12: 文件浏览器——读文件（v2.2.1-A 沙盒；文本，大小上限，二进制拒绝）
    elif action == "file_read":
        req_path = payload.get("path", "")
        try:
            content, truncated = _read_text_file(req_path)
            await ws_relay.send(json.dumps({
                "type": "file_read_result",
                "req_id": req_id,
                "path": os.path.realpath(os.path.abspath(os.path.expanduser(req_path))),
                "content": content,
                "truncated": truncated
            }))
        except PathNotAllowedError as e:
            logger.warning(f"file_read blocked (sandbox): {req_path}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "PATH_NOT_ALLOWED",
                "req_id": req_id,
                "message": str(e)
            }))
        except Exception as e:
            logger.warning(f"file_read failed for {req_path}: {e}")
            await ws_relay.send(json.dumps({
                "type": "error",
                "code": "FILE_READ_ERROR",
                "req_id": req_id,
                "message": f"读取文件失败: {e}"
            }))

    # P2-15: 连接诊断——桌面端自检 OpenCode 服务健康度
    elif action == "diagnose":
        try:
            is_healthy, version, err = await check_opencode_health(
                http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD
            )
            await ws_relay.send(json.dumps({
                "type": "diagnose_result",
                "req_id": req_id,
                "opencode_ok": is_healthy,
                "opencode_version": version or "",
                "opencode_error": err or "",
                "relay_ok": True,
            }))
        except Exception as e:
            logger.warning(f"diagnose failed: {e}")
            await ws_relay.send(json.dumps({
                "type": "diagnose_result",
                "req_id": req_id,
                "opencode_ok": False,
                "opencode_version": "",
                "opencode_error": str(e),
                "relay_ok": True,
            }))

# ==============================================================================
# v4.0: hello 能力协商
# ==============================================================================

async def do_hello_handshake(ws) -> dict:
    """
    v4.0: 连接建立后先发送 hello，等待 hello_ack。
    返回服务端能力列表。服务端无 hello_ack（v2 旧 relay）时抛异常，
    调用方记录明确错误并进入重连等待（v4 agent 要求 v3.0+ relay）。
    """
    hello = {
        "type": "hello",
        "v": config.PROTOCOL_VERSION,
        "capabilities": config.AGENT_CAPABILITIES,
        "device_id": config.get_desktop_device_id(),
    }
    await ws.send(json.dumps(hello))
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout=8.0)
        resp = json.loads(raw)
    except (asyncio.TimeoutError, json.JSONDecodeError) as e:
        raise RuntimeError(f"v4.0 hello 无响应（需要 v3.0+ relay）: {e}")
    except Exception as e:
        # v2 旧 relay 会直接关闭连接（首包必须为 hello）
        raise RuntimeError(f"v4.0 hello 失败（需要 v3.0+ relay）: {type(e).__name__}: {e}")
    if resp.get("type") != "hello_ack":
        raise RuntimeError(
            f"v4.0 hello 被拒绝（type={resp.get('type')}，需要 v3.0+ relay）")
    server_caps = resp.get("server_capabilities", [])
    logger.info(f"v4.0 hello_ack: server_v={resp.get('v')} caps={server_caps}")
    return server_caps


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
        is_healthy, version, err = await check_opencode_health(http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD)
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
                    # v4.0: 先 hello 能力协商，再 auth
                    await do_hello_handshake(ws)
                    auth_message = {
                        "type": "auth",
                        "account_id": account_id,
                        "secret": secret,
                        "client_type": "desktop",
                        "device_id": config.get_desktop_device_id(),
                    }
                    # B-8: 若配置了建房管理令牌则一并上报
                    if config.RELAY_ADMIN_TOKEN:
                        auth_message["admin_token"] = config.RELAY_ADMIN_TOKEN
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
            await do_hello_handshake(ws)
            auth_message = {"type": "auth", "account_id": account_id,
                            "secret": secret, "client_type": "desktop",
                            "device_id": config.get_desktop_device_id()}
            if config.RELAY_ADMIN_TOKEN:
                auth_message["admin_token"] = config.RELAY_ADMIN_TOKEN
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


