import base64
import json
import logging
from typing import AsyncGenerator, List, Dict, Any, Tuple, Optional
import aiohttp

from endpoints import build_url, ENDPOINTS

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
    health_url = build_url("health", base_url)
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
    url = build_url("session_list", base_url)
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
    url = build_url("session_create", base_url)
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
    url = build_url("session_abort", base_url, id=session_id)
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

    N-2: 按官方 OpenAPI 契约（opencode 1.18.34 /doc 实测），请求体只能是
    {"response": "once"|"always"|"reject"} 且 additionalProperties=false。
    旧代码多发的 action/reason 字段会被严格服务端 400 拒绝。
    映射：同意 -> "once"，拒绝 -> "reject"；reason 仅记本地日志，不发送。
    """
    url = build_url("permission_respond", base_url, id=session_id, permissionID=permission_id)
    headers = get_auth_headers(password)
    payload = {"response": "once" if allow else "reject"}
    if reason:
        logger.info(f"Permission {permission_id} decision allow={allow}, reason: {reason}")

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
    向 OpenCode 真实端点 POST /session/:id/prompt_async 发送用户提示词
    按照标准契约使用 parts: [{type: 'text', text: prompt}]
    N-5: 改用异步接口（opencode 1.18.34 /doc 实测存在，立即返回 204），
    彻底解决长任务超时误报问题：旧同步 /message 接口阻塞到模型回复完成，
    超过 300 秒的长任务会被误报 EXECUTION_ERROR。输出全量走 /event 事件流。
    v1.6 P1: 支持按消息指定 model {providerID, modelID} 与 agent（prompt_async 原生支持）。
    """
    url = build_url("prompt_async", base_url, id=session_id)
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

    # N-5: 异步接口立即返回 204，无需长超时
    async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=30.0)) as resp:
        if resp.status in (200, 201, 202, 204):
            return {"status": "accepted"}
        err_text = await resp.text()
        raise RuntimeError(f"OpenCode POST /session/{session_id}/prompt_async returned HTTP {resp.status}: {err_text}")


# N-3: SSE 端点路径缓存。不同 opencode 版本的事件流端点为 /event 或 /global/event，
# 首次订阅时探测一次并记住，避免每次重连都探测。
_resolved_event_path: Optional[str] = None


async def _resolve_event_url(
    session: aiohttp.ClientSession,
    base_url: str,
    password: Optional[str] = None,
) -> str:
    """N-3: 探测 SSE 端点路径（/event 或 /global/event），返回可用的 path 并缓存。"""
    global _resolved_event_path
    if _resolved_event_path:
        return _resolved_event_path
    clean = base_url.rstrip("/")
    headers = get_auth_headers(password)
    headers["Accept"] = "text/event-stream"
    for path in ENDPOINTS["event_stream"].candidates:  # B1: 候选路径走配置表
        try:
            async with session.get(
                f"{clean}{path}", headers=headers,
                timeout=aiohttp.ClientTimeout(total=5.0),
            ) as resp:
                # 200 即视为可用（SSE 长连接会保持打开；读到 headers 即够）
                if resp.status == 200:
                    logger.info(f"SSE endpoint resolved: {path}")
                    _resolved_event_path = path
                    return path
                logger.debug(f"SSE probe {path} -> HTTP {resp.status}")
        except Exception as e:
            logger.debug(f"SSE probe {path} failed: {e}")
    raise RuntimeError("无法找到 SSE 事件端点（已尝试 /event 与 /global/event）")


class SSEFrameParser:
    """SSE 帧解析状态机（v5.0.2 抽出，便于单测）。

    规范要点：一个事件由若干行组成，以**空行**结束；多条 `data:` 行属于同一事件，
    用 `\n` 连接后再交给上层。原实现逐行 json.loads，遇到多行 data 时每一行都不是
    合法 JSON，于是被静默吞掉——整个事件丢失且没有任何日志。

    用法：对每一行调用 feed()（不含换行符），返回完整事件对象或 None；
    流结束时调用 flush() 处理服务端没发结尾空行的情况。
    """

    def __init__(self) -> None:
        self._data: list = []

    def feed(self, line: str):
        if line.startswith("data:"):
            self._data.append(line[5:].lstrip())
            return None
        # 注释(":")、事件名(event:)、重连间隔(retry:)、id: 都不影响数据内容
        if line.startswith((":", "event:", "retry:", "id:")):
            return None
        if line == "":
            return self._emit()
        return None

    def flush(self):
        return self._emit()

    def _emit(self):
        if not self._data:
            return None
        data_str = "\n".join(self._data)
        self._data = []
        stripped = data_str.strip()
        if not stripped or stripped == "[DONE]":
            return None
        return json.loads(data_str)


async def subscribe_events_stream(
    session: aiohttp.ClientSession,
    base_url: str = DEFAULT_OPENCODE_BASE_URL,
    password: Optional[str] = None,
    last_event_id: Optional[str] = None,
    event_id_sink: Optional[Dict[str, str]] = None
) -> AsyncGenerator[Dict[str, Any], None]:
    """
    订阅 SSE 事件流（GET /event 或 /global/event，自动探测）
    监听 message.part.delta (增量Token)、permission.asked (工具授权请求) 与 session.idle
    B-10: 解析 SSE id: 行并写入 event_id_sink，供断线重连时作为 Last-Event-ID 续传
    N-3: 先探测 /event 与 /global/event，取可用者订阅（不同版本路径不同）
    """
    clean_url = base_url.rstrip("/")
    event_path = await _resolve_event_url(session, clean_url, password)
    url = f"{clean_url}{event_path}"
    headers = get_auth_headers(password)
    headers["Accept"] = "text/event-stream"
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id

    async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=None)) as resp:
        if resp.status != 200:
            err_text = await resp.text()
            # N-3: 探测时可用、订阅时失败 -> 清缓存，下次重探
            global _resolved_event_path
            _resolved_event_path = None
            raise RuntimeError(f"Failed to subscribe to {event_path} stream (HTTP {resp.status}): {err_text}")

        _parser = SSEFrameParser()
        async for line_bytes in resp.content:
            line = line_bytes.decode("utf-8", errors="replace").rstrip("\r\n")

            # B-10: 记录 SSE 事件游标（id: 行不参与数据拼接）
            if line.startswith("id:"):
                eid = line[3:].strip()
                if eid and event_id_sink is not None:
                    event_id_sink["last_event_id"] = eid
                continue

            try:
                event_data = _parser.feed(line)
            except Exception as e:
                logger.warning(f"SSE 帧 JSON 解析失败（已丢弃）: {e}")
                continue
            if event_data is not None:
                yield event_data

        # 流结束时若还有未闭合的 data（服务端没发结尾空行），补一次解析
        try:
            tail = _parser.flush()
        except Exception as e:
            logger.warning(f"SSE 尾帧 JSON 解析失败（已丢弃）: {e}")
            tail = None
        if tail is not None:
            yield tail


# ============================================================================
# v1.6 P1 Model/Agent 管理
# ============================================================================
# N-6: 元信息类函数统一返回 (data, error) 二元组；非 200 时打 warning（带状态码与路径），
# 调用方据此区分「真的没有」与「取不到」，不在 App 侧显示空白列表掩盖故障。
async def get_agents(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> tuple:
    """GET /agent — 获取可用 Agent 列表（含自定义 Agent）。返回 (agents, error)。"""
    url = build_url("agent_list", base_url)
    headers = get_auth_headers(password)
    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
            if resp.status == 200:
                data = await resp.json()
                return (data if isinstance(data, list) else data.get("data", [])), None
            logger.warning(f"N-6: GET {url} -> HTTP {resp.status}，Agent 列表取不到")
            return [], f"HTTP {resp.status}"
    except Exception as e:
        logger.warning(f"N-6: GET {url} 异常: {e}")
        return [], str(e)


async def get_providers(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> tuple:
    """GET /config/providers — 获取 Provider 与 Model 列表（动态，非硬编码）。返回 (providers, error)。"""
    url = build_url("providers", base_url)
    headers = get_auth_headers(password)
    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
            if resp.status == 200:
                return await resp.json(), None
            logger.warning(f"N-6: GET {url} -> HTTP {resp.status}，Provider 列表取不到")
            return {}, f"HTTP {resp.status}"
    except Exception as e:
        logger.warning(f"N-6: GET {url} 异常: {e}")
        return {}, str(e)


# ============================================================================
# v1.6 P1 项目管理中心
# ============================================================================
async def get_projects(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> tuple:
    """GET /project — 获取项目列表。返回 (projects, error)。"""
    url = build_url("project_list", base_url)
    headers = get_auth_headers(password)
    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
            if resp.status == 200:
                data = await resp.json()
                return (data if isinstance(data, list) else data.get("data", [])), None
            logger.warning(f"N-6: GET {url} -> HTTP {resp.status}，项目列表取不到")
            return [], f"HTTP {resp.status}"
    except Exception as e:
        logger.warning(f"N-6: GET {url} 异常: {e}")
        return [], str(e)


async def get_current_project(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> tuple:
    """GET /project/current — 获取当前项目。返回 (project, error)。"""
    url = build_url("project_current", base_url)
    headers = get_auth_headers(password)
    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
            if resp.status == 200:
                return await resp.json(), None
            logger.warning(f"N-6: GET {url} -> HTTP {resp.status}，当前项目取不到")
            return {}, f"HTTP {resp.status}"
    except Exception as e:
        logger.warning(f"N-6: GET {url} 异常: {e}")
        return {}, str(e)


async def get_vcs_info(
    session: aiohttp.ClientSession,
    base_url: str = "http://127.0.0.1:4096",
    password: Optional[str] = None,
) -> tuple:
    """GET /vcs — 获取当前项目的 Git 分支与工作区状态（项目管理中心用）。返回 (vcs, error)。"""
    url = build_url("vcs", base_url)
    headers = get_auth_headers(password)
    try:
        async with session.get(url, headers=headers, timeout=aiohttp.ClientTimeout(total=10.0)) as resp:
            if resp.status == 200:
                return await resp.json(), None
            logger.warning(f"N-6: GET {url} -> HTTP {resp.status}，VCS 信息取不到")
            return {}, f"HTTP {resp.status}"
    except Exception as e:
        logger.warning(f"N-6: GET {url} 异常: {e}")
        return {}, str(e)
