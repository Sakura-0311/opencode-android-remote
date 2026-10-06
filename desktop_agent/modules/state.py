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
    """E2EE 模块懒加载：仅 E2EE_ENABLED=1 时 import。

    v5.0.1 fail-closed：若用户显式开了 E2EE 但 cryptography 不可用，
    绝不静默降级为明文（那会让用户以为自己在加密），直接拒绝启动。
    """
    global _e2ee_mod, _e2ee_tried
    if os.getenv("E2EE_ENABLED", "0") != "1":
        return None
    if not _e2ee_tried:
        _e2ee_tried = True
        try:
            from modules import e2ee as _mod
            _e2ee_mod = _mod
        except ImportError as e:
            raise SystemExit(
                "E2EE_ENABLED=1 但 cryptography 不可用，拒绝以明文运行（fail-closed）。\n"
                "  修复：pip install cryptography\n"
                "  或显式关闭：E2EE_ENABLED=0\n"
                f"  原始错误：{e}"
            ) from e
    return _e2ee_mod


async def send_d2m_secure(ws_relay, msg: dict) -> bool:
    """d2m 内容型消息的统一发送出口（v5.0.1 起 fail-closed，v5.0.2 支持多对端）。

    - E2EE 未开启 / 未协商任何对端 / 非内容型消息：单条广播（旧行为）。
    - 已协商 N 个对端且是内容型消息：**为每个对端各加密一份**，并用
      `target_device_id` 定向发给对应手机（relay 侧据此只投递给那一台，
      缓冲补发也按目标过滤）。其他手机永远收不到自己解不开的密文。
    - 任何一份加密失败：整体拒发并回报 E2EE_UNAVAILABLE，绝不降级明文。

    返回是否真的发出。
    """
    _em = _e2ee()
    if _em is None:
        await ws_relay.send(json.dumps(msg))
        return True

    peers = _em.list_peer_ids()
    if not peers or msg.get("type") not in _em.ENCRYPTABLE_D2M:
        # E2EE 未真正生效，或本条按设计不加密（error/pong/stream_start…）
        await ws_relay.send(json.dumps(msg))
        return True

    for peer in peers:
        try:
            sealed = _em.encrypt_outgoing(msg, peer_device_id=peer, strict=True)
        except Exception as e:
            logger.error(f"E2EE fail-closed: 已阻止明文外发 {msg.get('type')}: {e}")
            try:
                await ws_relay.send(json.dumps({
                    "type": "error",
                    "code": "E2EE_UNAVAILABLE",
                    "session_id": msg.get("session_id", "default"),
                    "message": f"端到端加密不可用，已阻止本条消息明文外发：{e}",
                }))
            except Exception:
                pass
            return False
        # 路由元数据（不进入密文内层）：只让这一台手机收到
        sealed["target_device_id"] = peer
        await ws_relay.send(json.dumps(sealed))
    return True

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

# v4.7.0/A5: 只在调用方还没配过 handler 时给默认配置（import 时不改写 root logger）
if not logging.getLogger().handlers:
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

# v4.7.0/A2: 空注册表告警节流时间戳
_empty_guard_warned_at = 0.0

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

# v5.0.1: SSE 事件名契约——集中定义便于单测锁定。
# 上游真实名（message.part.updated / permission.updated）在前，旧名保留兼容。
DELTA_EVENT_TYPES = ("message.part.updated", "message.part.delta", "delta", "stream_chunk")
PERMISSION_EVENT_TYPES = ("permission.updated", "permission.asked",
                          "permission.request", "permission")


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


def _diff_lines_from(raw: str) -> list:
    """把统一 diff 文本转成手机端 CompactDiffView 需要的行结构。

    v5.0.1: "--- a/…" / "+++ b/…" 是文件头，不是增删行——此前被渲染成
    一行红一行绿，看起来像被改了两行，现在归到 HEADER。
    """
    lines = []
    for line in (raw or "").split("\n"):
        if line.startswith(("--- ", "+++ ")) or line.startswith("@@"):
            lines.append({"type": "HEADER", "content": line})
        elif line.startswith("+"):
            lines.append({"type": "ADDED", "content": line[1:]})
        elif line.startswith("-"):
            lines.append({"type": "REMOVED", "content": line[1:]})
        else:
            lines.append({"type": "UNCHANGED", "content": line})
    return lines


def parse_permission_event(event: Dict[str, Any]) -> Dict[str, Any]:
    """解析工具审批事件（纯函数，便于单测）。

    v5.0.1 对齐上游真实事件 `permission.updated`：properties 就是扁平的
    Permission 对象 {id,type,pattern?,sessionID,messageID,callID?,title,
    metadata,time{created}}。此前只认 permission.asked/permission.request
    并去读不存在的 tool.name / diff / file_path，导致手机端不弹审批框、
    或弹出「无内容的审批框」让用户盲签。

    metadata 是官方扩展字段袋：bash 的真实命令在 metadata.command，
    edit 的差异在 metadata.diff。缺失时不臆造，保持 None/空串。
    """
    props = event.get("properties") or {}
    if not isinstance(props, dict):
        props = {}

    def _pkey(*keys):
        for k in keys:
            v = props.get(k)
            if v:
                return v
        for k in keys:
            v = event.get(k)
            if v:
                return v
        return None

    meta = props.get("metadata") or event.get("metadata") or {}
    if not isinstance(meta, dict):
        meta = {}
    tool_info = props.get("tool") or event.get("tool") or {}
    if not isinstance(tool_info, dict):
        tool_info = {}

    permission_id = _pkey("id", "permission_id", "permissionID") or secrets.token_hex(8)
    # 注意：不能从事件顶层取 "type"——那是事件名（permission.updated），
    # 官方权限种类只在 properties.type 里；取错会让每张审批卡都显示
    # "permission.updated" 而不是 bash/edit。
    tool_name = (props.get("type") or props.get("tool_name") or event.get("tool_name")
                 or tool_info.get("name") or "sensitive_tool")
    file_path = (_pkey("file_path", "filePath") or tool_info.get("path")
                 or meta.get("filePath") or meta.get("file_path"))
    command = meta.get("command") or meta.get("cmd")
    patterns = _pkey("pattern") or meta.get("patterns") or meta.get("pattern")
    title = _pkey("title", "summary")
    summary = (title or tool_info.get("description")
               or (f"请求执行命令：{command}" if command else None)
               or "申请执行本地文件修改或终端命令")
    # 优先真实 diff；无 diff 时把待执行命令放进详情区，避免空审批框
    raw_diff = (_pkey("diff", "raw_content") or meta.get("diff")
                or (command if command else ""))
    return {
        "permission_id": str(permission_id),
        "tool_name": str(tool_name),
        "file_path": file_path,
        "summary": summary,
        "command": command,
        "patterns": patterns,
        "raw_content": raw_diff,
        "diff_lines": _diff_lines_from(raw_diff),
    }


# ==============================================================================
# B-10: SSE 游标持久化（断线重连断点续传）
# ==============================================================================
# v4.7.0/A1: 默认放配置目录（而非当前工作目录）；OPENCODE_SSE_CURSOR_FILE 可覆盖
def _default_cursor_file() -> str:
    d = os.path.join(os.path.expanduser("~"), ".config", "opencode-remote")
    try:
        os.makedirs(d, mode=0o700, exist_ok=True)
    except OSError:
        pass
    return os.path.join(d, ".opencode_sse_cursor")

config.SSE_CURSOR_FILE = os.getenv("OPENCODE_SSE_CURSOR_FILE", _default_cursor_file())

def load_sse_cursor() -> Optional[str]:
    try:
        with open(config.SSE_CURSOR_FILE, "r", encoding="utf-8") as f:
            return f.read().strip() or None
    except Exception:
        return None

# v4.7.0/A1: 节流——最多每 3 秒写一次盘（流式输出每秒数十次事件时不阻塞事件循环）
_cursor_last_write = 0.0
_cursor_pending = None

def save_sse_cursor(event_id: str):
    global _cursor_last_write, _cursor_pending
    now = time.time()
    if now - _cursor_last_write < 3:
        _cursor_pending = event_id
        return
    _cursor_last_write = now
    _cursor_pending = None
    try:
        with open(config.SSE_CURSOR_FILE, "w", encoding="utf-8") as f:
            f.write(event_id)
    except Exception as e:
        logger.warning(f"Failed to persist SSE cursor: {e}")

def flush_sse_cursor():
    """v4.7.0/A1: 会话 idle / 重连时把被节流拦下的游标写盘。"""
    global _cursor_last_write, _cursor_pending
    if _cursor_pending:
        pending, _cursor_pending = _cursor_pending, None
        _cursor_last_write = 0.0  # 强制绕过节流
        save_sse_cursor(pending)


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
    # v4.7.0/A7: 重连退避（成功一次后重置）
    _reconnect_delay = 3.0
    while True:
        try:
            async for event in subscribe_events_stream(
                http_session, config.OPENCODE_API_URL, config.OPENCODE_PASSWORD,
                last_event_id=last_id, event_id_sink=cursor_sink
            ):
                _reconnect_delay = 3.0  # 成功收到事件，退避重置
                # B-10: 收到新游标即持久化
                new_id = cursor_sink.get("last_event_id")
                if new_id and new_id != last_id:
                    last_id = new_id
                    save_sse_cursor(new_id)
                event_type = event.get("type", "")
                _now = time.time()
                # B-2: 会话 ID 优先从 properties 取；B-3: 无归属或非已知会话的事件直接丢弃，防串台
                # v4.7.0/A2: 注册表为空时（启动初期 / 1 小时无活跃被清理）守卫失效——
                # 打警告日志；AGENT_STRICT_SESSION_GUARD=1 时空注册表=丢弃全部事件
                session_id = _extract_event_session(event)
                if not session_id:
                    continue
                if session_id not in known_session_ids:
                    if not known_session_ids:
                        if os.getenv("AGENT_STRICT_SESSION_GUARD", "0") == "1":
                            continue
                        # 告警节流：10 分钟一次，避免刷屏
                        global _empty_guard_warned_at
                        if _now - _empty_guard_warned_at > 600:
                            _empty_guard_warned_at = _now
                            logger.warning(
                                "A2: known_session_ids 为空，B-3 防串台守卫暂不生效 "
                                "(AGENT_STRICT_SESSION_GUARD=1 可改为丢弃)")
                    else:
                        continue
                # P1-10: 活跃会话刷新 last_seen；每 5 分钟清理过期条目
                known_session_ids.touch(session_id)
                now_ts = _now
                if now_ts - last_cleanup > 300:
                    last_cleanup = now_ts
                    known_session_ids.cleanup()

                # 1. 增量 Token 输出
                # v5.0.1: 补上游真实事件名 message.part.updated（带 properties.delta）。
                # 旧名 message.part.delta 保留兼容；两者都只取 delta，
                # 不取 part.text（否则每次全量重发会把同一段文本刷多遍）。
                if event_type in DELTA_EVENT_TYPES:
                    delta_text = _extract_event_delta(event)
                    if delta_text:
                        _out = {
                            "type": "stream_chunk",
                            "session_id": session_id,
                            "chunk": delta_text
                        }
                        # v5.0.1: 统一 fail-closed 出口——加密不可用时报错，不再降级明文
                        await send_d2m_secure(ws_relay, _out)

                # 2. 工具权限请求
                # v5.0.1: 上游真实事件是 permission.updated，properties 为扁平
                # Permission {id,type,pattern?,sessionID,messageID,callID?,title,metadata,time}。
                # 旧名（permission.asked / permission.request / permission）保留兼容。
                elif event_type in PERMISSION_EVENT_TYPES:
                    _parsed = parse_permission_event(event)
                    permission_id = _parsed["permission_id"]
                    tool_name = _parsed["tool_name"]
                    file_path = _parsed["file_path"]
                    summary = _parsed["summary"]
                    raw_diff = _parsed["raw_content"]
                    command = _parsed["command"]
                    patterns = _parsed["patterns"]
                    diff_lines = _parsed["diff_lines"]

                    rec = tool_guard.create_approval(session_id, permission_id, tool_name, event)
                    logger.info(f"Forwarding tool approval request with Nonce to mobile: {tool_name} (call_id={permission_id})")
                    _appr_msg = {
                        "type": "tool_approval_request",
                        "session_id": session_id,
                        "call_id": permission_id,
                        "tool_name": tool_name,
                        "file_path": file_path,
                        "summary": summary,
                        "diff_lines": diff_lines,
                        "raw_content": raw_diff,
                        # v5.0.1: 官方 Permission 的额外信息（旧 App 忽略未知字段）
                        "command": command,
                        "patterns": patterns,
                        "nonce": rec["nonce"],
                        "expires_at": rec["expires_at"]
                    }
                    # v5.0.1: 统一 fail-closed 出口（审批含 nonce，绝不明文外发）
                    await send_d2m_secure(ws_relay, _appr_msg)

                # 3. 会话空闲或执行完成 (session.idle / message.complete)
                elif event_type in ("session.idle", "message.complete", "stream.end"):
                    # v4.7.0/A1: idle 时把被节流拦下的游标写盘
                    flush_sse_cursor()
                    await ws_relay.send(json.dumps({
                        "type": "stream_end",
                        "session_id": session_id
                    }))

        except asyncio.CancelledError:
            break
        except Exception as e:
            # v4.7.0/A7: 指数退避（3s 起，最大 60s）+ 降噪（只打 warning，不再每次刷堆栈）
            logger.warning(f"SSE /event 连接中断 ({e})，{_reconnect_delay:.0f}s 后重连")
            await asyncio.sleep(_reconnect_delay)
            _reconnect_delay = min(60.0, _reconnect_delay * 2)

# ==============================================================================
# 手机端消息调度与处理
# ==============================================================================
# ==============================================================================
# P2-12: 文件浏览器辅助函数
# v2.2.1-A 文件沙盒：路径白名单 + 敏感文件黑名单
