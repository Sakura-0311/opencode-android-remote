"""
OpenCode Local Server API Client & Configuration
================================================
用于与电脑本地运行的 opencode serve 进程进行通信。

【重要提示】
不同版本的 OpenCode（或 open-interpreter / aider 等兼容后端）API 路径与字段可能存在差异。
请根据你本地运行的 OpenCode 版本核对以下路径与字段配置。
可通过环境变量进行覆盖，例如：
  export OPENCODE_API_URL="http://127.0.0.1:4096"
  export OPENCODE_MSG_ENDPOINT="/sessions/{session_id}/message"
"""

import asyncio
import json
import logging
import os
from typing import AsyncGenerator, Optional, Tuple

try:
    import aiohttp
except ImportError:
    aiohttp = None  # type: ignore

logger = logging.getLogger("OpenCodeAPI")

# ==============================================================================
# P1-5: 可配置 API 路径映射常量
# 注：不同版本 opencode serve 的路径可能不同（如 /session/:id/message vs /sessions/:id/message）
# 请按你本地 opencode serve 的实际版本核对以下路径与字段
# ==============================================================================

# 默认本地 OpenCode 服务地址
DEFAULT_OPENCODE_BASE_URL: str = "http://127.0.0.1:4096"

# 健康检查 / 心跳接口 (常见路径: "/health", "/global/health", "/api/version")
ENDPOINT_HEALTH: str = os.getenv("OPENCODE_HEALTH_ENDPOINT", "/health")

# 会话列表接口 (常见路径: "/sessions", "/session")
ENDPOINT_SESSIONS: str = os.getenv("OPENCODE_SESSIONS_ENDPOINT", "/sessions")

# 发送消息 / 提示词接口 (常见路径: "/sessions/{session_id}/message", "/session/{session_id}/prompt")
ENDPOINT_MESSAGE: str = os.getenv("OPENCODE_MSG_ENDPOINT", "/sessions/{session_id}/message")

# 发送消息时的 JSON 字段名 (常见: "text" 或 "prompt")
MESSAGE_TEXT_KEY: str = os.getenv("OPENCODE_MSG_FIELD", "text")


async def check_opencode_health(
    http_session,
    base_url: str = DEFAULT_OPENCODE_BASE_URL
) -> Tuple[bool, Optional[str], Optional[str]]:
    """
    探测本地 opencode serve 是否存活，尽量带回版本信息；
    失败时 err 写清楚原因，不要抛异常。
    返回: (is_healthy: bool, version_info: Optional[str], err: Optional[str])
    """
    url = f"{base_url.rstrip('/')}{ENDPOINT_HEALTH}"
    try:
        if aiohttp is not None:
            timeout = aiohttp.ClientTimeout(total=3.0)
        else:
            timeout = 3.0

        async with http_session.get(url, timeout=timeout) as resp:
            if resp.status == 200:
                try:
                    data = await resp.json()
                    version = data.get("version") or data.get("status") or "running"
                    return True, str(version), None
                except Exception:
                    text = await resp.text()
                    return True, text.strip() or "200 OK", None
            else:
                err_text = await resp.text()
                return False, None, f"OpenCode 服务响应异常 (HTTP {resp.status}): {err_text[:200]}"
    except Exception as e:
        err_msg = str(e)
        if "Cannot connect to host" in err_msg or "Connection refused" in err_msg:
            return False, None, f"无法连接到本地服务 ({base_url})，端口 4096 未开放或未启动 opencode serve"
        return False, None, f"健康检查失败 ({base_url}): {err_msg}"


async def query_sessions(http_session, base_url: str = DEFAULT_OPENCODE_BASE_URL):
    """
    返回会话列表数据（透传 opencode 的返回结构）；失败时抛异常（调用方会兜底）。
    """
    url = f"{base_url.rstrip('/')}{ENDPOINT_SESSIONS}"
    if aiohttp is not None:
        timeout = aiohttp.ClientTimeout(total=5.0)
    else:
        timeout = 5.0

    async with http_session.get(url, timeout=timeout) as resp:
        if resp.status == 200:
            return await resp.json()
        err_text = await resp.text()
        raise RuntimeError(f"获取会话列表失败 (HTTP {resp.status}): {err_text[:200]}")


async def stream_opencode_response(
    http_session,
    session_id: str,
    prompt_text: str,
    base_url: str = DEFAULT_OPENCODE_BASE_URL
) -> AsyncGenerator[str, None]:
    """
    向本地 OpenCode 发送 Prompt 并获取 SSE / 流式响应 Chunk。
    async generator，每次 yield 一个 str 类型的文本 chunk。
    收到 asyncio.CancelledError 时必须直接向上传播（不要吞掉），
    保证上层的 TaskManager.cancel() 能真正中断请求。
    """
    path = ENDPOINT_MESSAGE.format(session_id=session_id)
    url = f"{base_url.rstrip('/')}{path}"
    payload = {MESSAGE_TEXT_KEY: prompt_text}

    logger.info(f"POST -> {url} with {MESSAGE_TEXT_KEY}='{prompt_text[:40]}...'")

    if aiohttp is not None:
        timeout = aiohttp.ClientTimeout(total=120.0)
    else:
        timeout = 120.0

    try:
        async with http_session.post(url, json=payload, timeout=timeout) as resp:
            if resp.status != 200:
                err_text = await resp.text()
                raise RuntimeError(f"OpenCode API returned HTTP {resp.status}: {err_text}")

            # 逐行读取 SSE 或数据流
            async for raw_line in resp.content:
                line = raw_line.decode("utf-8", errors="replace").strip()
                # 跳过空行和注释行
                if not line or line.startswith(":"):
                    continue

                # 处理 SSE 'data: ...' 格式
                if line.startswith("data:"):
                    data_content = line[5:].strip()
                    if data_content == "[DONE]":
                        break
                    try:
                        parsed = json.loads(data_content)
                        # 尝试提取 text / content / delta 文本字段
                        chunk = parsed.get("text") or parsed.get("content") or parsed.get("delta") or data_content
                        yield str(chunk)
                    except Exception:
                        yield data_content
                else:
                    yield line
    except asyncio.CancelledError:
        logger.info(f"stream_opencode_response received CancelledError for session {session_id}, propagating up.")
        raise
