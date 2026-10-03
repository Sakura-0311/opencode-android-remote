import base64
import json
import logging
from typing import AsyncGenerator, List, Dict, Any, Tuple, Optional
import aiohttp

logger = logging.getLogger("OpenCodeApi")

DEFAULT_OPENCODE_BASE_URL = "http://127.0.0.1:4096"

def get_auth_headers(password: Optional[str] = None) -> Dict[str, str]:
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json"
    }
    if password:
        token = base64.b64encode(f"opencode:{password}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {token}"
    return headers

async def check_opencode_health(
    session: aiohttp.ClientSession,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None
) -> Tuple[bool, str, Optional[str]]:
    """
    检查本地/云端 OpenCode 服务真实健康状态
    严格请求真实端点 GET /global/health
    只有 HTTP 200 且返回正常才判为健康，绝不将 404/500 等异常状态码伪装为成功！
    """
    clean_url = base_url.rstrip("/")
    health_url = f"{clean_url}/global/health"
    headers = get_auth_headers(password)

    try:
        async with session.get(health_url, headers=headers, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
            if resp.status == 200:
                text = await resp.text()
                try:
                    data = json.loads(text)
                    version = data.get("version", data.get("status", "healthy"))
                    return True, str(version), None
                except Exception:
                    return True, "200 OK", None
            elif resp.status == 401:
                return False, "AUTH_REQUIRED", "OpenCode 开启了密码保护，需要提供正确的访问密码 (OPENCODE_SERVER_PASSWORD)"
            elif resp.status == 404:
                return False, "ENDPOINT_NOT_FOUND", f"OpenCode 服务响应 404 Not Found，请确认运行的是官方 opencode serve (URL: {health_url})"
            else:
                return False, f"HTTP_{resp.status}", f"OpenCode 探活返回异常 HTTP 状态码: {resp.status}"
    except aiohttp.ClientConnectorError as e:
        return False, "CONNECTION_REFUSED", f"无法连接到 OpenCode 端口 (127.0.0.1:4096): 服务未启动或端口被占用 ({e})"
    except Exception as e:
        return False, "PROBE_FAILED", f"OpenCode 健康检查异常: {str(e)}"


async def query_sessions(
    session: aiohttp.ClientSession,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    请求真实端点 GET /session
    返回真实的 OpenCode 会话列表，失败时返回空列表，绝不硬编码虚假会话冒充真实数据！
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/session"
    headers = get_auth_headers(password)

    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
            if resp.status == 200:
                data = await resp.json()
                if isinstance(data, list):
                    return data
                elif isinstance(data, dict) and "sessions" in data:
                    return data["sessions"]
            else:
                logger.warning(f"GET /session returned HTTP {resp.status}")
    except Exception as e:
        logger.error(f"Failed to query real sessions from {url}: {e}")

    return []


async def create_session(
    session: aiohttp.ClientSession,
    title: str = "New Mobile Session",
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None
) -> Dict[str, Any]:
    """
    调用真实端点 POST /session 创建真实会话
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/session"
    headers = get_auth_headers(password)
    payload = {"title": title}

    async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
        if resp.status in (200, 201):
            return await resp.json()
        err_msg = await resp.text()
        raise RuntimeError(f"Failed to create session on OpenCode (HTTP {resp.status}): {err_msg}")


async def abort_session(
    session: aiohttp.ClientSession,
    session_id: str,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None
) -> bool:
    """
    调用真实端点 POST /session/:id/abort 真正停止底层正在运行的模型生成与工具执行
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/session/{session_id}/abort"
    headers = get_auth_headers(password)

    try:
        async with session.post(url, json={}, headers=headers, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
            logger.info(f"Aborted session {session_id}, HTTP status: {resp.status}")
            return resp.status in (200, 204)
    except Exception as e:
        logger.error(f"Failed to abort session {session_id}: {e}")
        return False


async def respond_to_permission(
    session: aiohttp.ClientSession,
    session_id: str,
    permission_id: str,
    allow: bool,
    reason: str = "",
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None
) -> bool:
    """
    调用真实端点 POST /session/:id/permissions/:permID 回传用户授权决定
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/session/{session_id}/permissions/{permission_id}"
    headers = get_auth_headers(password)
    payload = {
        "action": "allow" if allow else "deny",
        "response": "allow" if allow else "deny",
        "reason": reason
    }

    try:
        async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
            logger.info(f"Responded to permission {permission_id} (allow={allow}), status={resp.status}")
            return resp.status in (200, 204)
    except Exception as e:
        logger.error(f"Failed to respond to permission {permission_id}: {e}")
        return False


async def send_session_message_async(
    session: aiohttp.ClientSession,
    session_id: str,
    prompt: str,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None,
    model: Optional[dict] = None,
    agent: Optional[str] = None
) -> Dict[str, Any]:
    """
    向 OpenCode 真实端点 POST /session/:id/message 发送用户提示词
    按照标准契约使用 parts: [{type: 'text', text: prompt}]
    B-6: 该端点为同步阻塞语义（等待模型回复完成才返回），超时放宽到 300 秒；
    装依赖、跑测试等长任务不再误报 EXECUTION_ERROR。输出仍全量走 /event 事件流。
    v1.6 P1: 支持按消息指定 model {providerID, modelID} 与 agent。
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/session/{session_id}/message"
    headers = get_auth_headers(password)
    payload = {
        "parts": [
            {
                "type": "text",
                "text": prompt
            }
        ]
    }
    # v1.6 P1: 透传模型与 Agent 选择
    if model and isinstance(model, dict) and model.get("modelID"):
        payload["model"] = {
            "providerID": model.get("providerID", ""),
            "modelID": model.get("modelID", ""),
        }
    if agent:
        payload["agent"] = agent

    async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=300.0)) as resp:
        if resp.status in (200, 201, 202):
            try:
                return await resp.json()
            except Exception:
                return {"status": "ok"}
        err_text = await resp.text()
        raise RuntimeError(f"OpenCode POST /session/{session_id}/message returned HTTP {resp.status}: {err_text}")


async def subscribe_events_stream(
    session: aiohttp.ClientSession,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None,
    last_event_id: Optional[str] = None,
    event_id_sink: Optional[Dict[str, str]] = None
) -> AsyncGenerator[Dict[str, Any], None]:
    """
    订阅真实端点 GET /event (SSE 事件流)
    监听 message.part.delta (增量Token)、permission.asked (工具授权请求) 与 session.idle
    B-10: 解析 SSE id: 行并写入 event_id_sink，供断线重连时作为 Last-Event-ID 续传
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/event"
    headers = get_auth_headers(password)
    headers["Accept"] = "text/event-stream"
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id

    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=None)) as resp:
        if resp.status != 200:
            err_text = await resp.text()
            raise RuntimeError(f"Failed to subscribe to /event stream (HTTP {resp.status}): {err_text}")

        async for line_bytes in resp.content:
            line = line_bytes.decode("utf-8", errors="replace").strip()
            if not line:
                continue

            # B-10: 记录 SSE 事件游标
            if line.startswith("id:"):
                eid = line[3:].strip()
                if eid and event_id_sink is not None:
                    event_id_sink["last_event_id"] = eid
                continue

            if line.startswith("data:"):
                data_str = line[5:].strip()
                if not data_str or data_str == "[DONE]":
                    continue
                try:
                    event_data = json.loads(data_str)
                    yield event_data
                except Exception:
                    pass


# ============================================================================
# v1.6 P1 Model/Agent 管理
# ============================================================================
async def get_agents(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> list:
    """GET /agent — 获取可用 Agent 列表（含自定义 Agent）。"""
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/agent"
    headers = get_auth_headers(password)
    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
        if resp.status == 200:
            data = await resp.json()
            return data if isinstance(data, list) else data.get("data", [])
        return []


async def get_providers(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> dict:
    """GET /config/providers — 获取 Provider 与 Model 列表（动态，非硬编码）。"""
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/config/providers"
    headers = get_auth_headers(password)
    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
        if resp.status == 200:
            return await resp.json()
        return {}


# ============================================================================
# v1.6 P1 项目管理中心
# ============================================================================
async def get_projects(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> list:
    """GET /project — 获取项目列表。"""
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/project"
    headers = get_auth_headers(password)
    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
        if resp.status == 200:
            data = await resp.json()
            return data if isinstance(data, list) else data.get("data", [])
        return []


async def get_current_project(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> dict:
    """GET /project/current — 获取当前项目。"""
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/project/current"
    headers = get_auth_headers(password)
    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
        if resp.status == 200:
            return await resp.json()
        return {}


async def get_vcs_info(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> dict:
    """GET /vcs — 获取当前项目的 Git 分支与工作区状态（项目管理中心用）。"""
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/vcs"
    headers = get_auth_headers(password)
    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
        if resp.status == 200:
            return await resp.json()
        return {}
