import json
import logging
from typing import AsyncGenerator, List, Dict, Any, Tuple, Optional
import aiohttp

logger = logging.getLogger("OpenCodeApi")

DEFAULT_OPENCODE_BASE_URL = "http://127.0.0.1:4096"

async def check_opencode_health(session: aiohttp.ClientSession, base_url: str = DEFAULT_OPENCODE_BASE_URL) -> Tuple[bool, str, Optional[str]]:
    """
    检查本地 OpenCode 服务健康状态
    返回 (is_healthy, version_or_info, error_msg)
    """
    clean_url = base_url.rstrip("/")
    probe_urls = [f"{clean_url}/health", f"{clean_url}/api/health", f"{clean_url}/"]
    
    for url in probe_urls:
        try:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=4.0)) as resp:
                if resp.status < 500:
                    text = await resp.text()
                    try:
                        data = json.loads(text)
                        version = data.get("version", data.get("status", f"HTTP {resp.status}"))
                        return True, str(version), None
                    except Exception:
                        return True, f"HTTP {resp.status}", None
        except Exception as e:
            continue

    return False, "OFFLINE", "Connection refused or timed out at " + base_url


async def query_sessions(session: aiohttp.ClientSession, base_url: str = DEFAULT_OPENCODE_BASE_URL) -> List[Dict[str, Any]]:
    """
    查询 OpenCode 当前已存在的会话列表
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/api/sessions"
    try:
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=5.0)) as resp:
            if resp.status == 200:
                data = await resp.json()
                if isinstance(data, list):
                    return data
                elif isinstance(data, dict) and "sessions" in data:
                    return data["sessions"]
    except Exception as e:
        logger.debug(f"Query sessions error: {e}")

    # 默认兜底会话
    return [
        {"id": "default", "title": "Main Project Workspace", "tag": "默认", "isPinned": True}
    ]


async def stream_opencode_response(
    session: aiohttp.ClientSession,
    session_id: str,
    prompt: str,
    base_url: str = DEFAULT_OPENCODE_BASE_URL
) -> AsyncGenerator[str, None]:
    """
    调用本地 OpenCode 服务并流式接收输出（SSE / Chunked）
    """
    clean_url = base_url.rstrip("/")
    url = f"{clean_url}/api/chat"
    
    payload = {
        "session_id": session_id,
        "prompt": prompt,
        "stream": True
    }
    
    headers = {
        "Accept": "text/event-stream, application/json, text/plain",
        "Content-Type": "application/json"
    }

    async with session.post(url, json=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=300.0)) as resp:
        if resp.status >= 400:
            err_text = await resp.text()
            raise RuntimeError(f"OpenCode API returned HTTP {resp.status}: {err_text}")

        # 读取 SSE 响应流
        async for line_bytes in resp.content:
            line = line_bytes.decode("utf-8", errors="replace")
            if not line:
                continue

            # 处理 SSE 协议行
            trimmed = line.strip()
            if trimmed.startswith("data:"):
                data_part = trimmed[5:].strip()
                if data_part == "[DONE]":
                    break
                try:
                    obj = json.loads(data_part)
                    chunk = obj.get("content", obj.get("chunk", obj.get("text", data_part)))
                    yield chunk
                except Exception:
                    yield data_part
            elif trimmed:
                yield line
