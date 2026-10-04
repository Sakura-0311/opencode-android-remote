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


_e2ee_mod = None
_e2ee_tried = False


def _e2ee():
    """E2EE 模块懒加载：仅 E2EE_ENABLED=1 时 import（cryptography 缺失则降级明文）。"""
    global _e2ee_mod, _e2ee_tried
    if os.getenv("E2EE_ENABLED", "0") != "1":
        return None
    if not _e2ee_tried:
        _e2ee_tried = True
        try:
            from modules import e2ee as _mod
            _e2ee_mod = _mod
        except ImportError:
            _e2ee_mod = None
    return _e2ee_mod

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
class KnownSessionRegistry:
    """
    P1-10: 已知会话注册表（B-3 防串台守卫用）。
    每个条目带 last_seen；超过 ttl 未活跃即过期清理，防止集合无限增长。
    本进程即单 account/device 的 agent 实例，隔离天然成立。
    """
    def __init__(self, ttl_sec: float = 3600.0):
        self.ttl_sec = ttl_sec
        self._seen: Dict[str, float] = {}

    def add(self, session_id: str) -> None:
        self._seen[session_id] = time.time()

    def touch(self, session_id: str) -> None:
        if session_id in self._seen:
            self._seen[session_id] = time.time()

    def cleanup(self) -> int:
        now = time.time()
        expired = [sid for sid, ts in self._seen.items() if now - ts > self.ttl_sec]
        for sid in expired:
            del self._seen[sid]
        if expired:
            logger.info(f"P1-10: cleaned {len(expired)} expired known sessions")
        return len(expired)

    def __contains__(self, session_id: object) -> bool:
        return session_id in self._seen

    def __bool__(self) -> bool:
        return bool(self._seen)

    def __len__(self) -> int:
        return len(self._seen)


known_session_ids = KnownSessionRegistry(ttl_sec=config.KNOWN_SESSION_TTL_SEC)

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
config.SSE_CURSOR_FILE = os.getenv("OPENCODE_SSE_CURSOR_FILE", ".opencode_sse_cursor")

def load_sse_cursor() -> Optional[str]:
    try:
        with open(config.SSE_CURSOR_FILE, "r", encoding="utf-8") as f:
            return f.read().strip() or None
    except Exception:
        return None

def save_sse_cursor(event_id: str):
    try:
        with open(config.SSE_CURSOR_FILE, "w", encoding="utf-8") as f:
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
    last_cleanup = time.time()
    while True:
        try:
            async for event in subscribe_events_stream(
                http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD,
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
                # P1-10: 活跃会话刷新 last_seen；每 5 分钟清理过期条目
                known_session_ids.touch(session_id)
                now_ts = time.time()
                if now_ts - last_cleanup > 300:
                    last_cleanup = now_ts
                    known_session_ids.cleanup()

                # 1. 增量 Token 输出 (message.part.delta)
                if event_type in ("message.part.delta", "delta", "stream_chunk"):
                    delta_text = _extract_event_delta(event)
                    if delta_text:
                        _out = {
                            "type": "stream_chunk",
                            "session_id": session_id,
                            "chunk": delta_text
                        }
                        # E2EE 发消息钩子：已启用且已协商时加密 chunk，
                        # 明文字段（type/session_id/relay_seq/source_device_id）保留供 relay 路由
                        _em = _e2ee()
                        if _em is not None:
                            try:
                                _out = _em.encrypt_outgoing(_out)
                            except Exception as e:
                                logger.warning(f"E2EE 加密 stream_chunk 失败，走明文: {e}")
                        await ws_relay.send(json.dumps(_out))

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
# ==============================================================================
# P2-12: 文件浏览器辅助函数
# v2.2.1-A 文件沙盒：路径白名单 + 敏感文件黑名单
