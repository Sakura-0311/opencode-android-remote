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
# ==============================================================================
# ==============================================================================
# P2-12: 文件浏览器辅助函数
# v2.2.1-A 文件沙盒：路径白名单 + 敏感文件黑名单
# ==============================================================================
config.FILE_READ_MAX_BYTES = int(os.getenv("AGENT_FILE_READ_MAX_BYTES", str(200 * 1024)))

_file_roots_cache = None
def _get_file_roots() -> list:
    """允许访问的根目录列表。默认 = agent 启动时的工作目录（OpenCode 项目目录）。
    可用 AGENT_FILE_ROOTS 环境变量覆盖（os.pathsep 分隔多个），用时打印一次警告。"""
    global _file_roots_cache
    if _file_roots_cache is not None:
        return _file_roots_cache
    env_roots = os.getenv("AGENT_FILE_ROOTS", "").strip()
    if env_roots:
        roots = [os.path.realpath(os.path.abspath(os.path.expanduser(r)))
                 for r in env_roots.split(os.pathsep) if r.strip()]
        logger.warning("[v2.2.1-A] AGENT_FILE_ROOTS 兜底开关已启用，文件浏览器根目录被放宽: %s", roots)
    else:
        roots = [os.path.realpath(os.getcwd())]
    _file_roots_cache = [r for r in roots if os.path.isdir(r)] or [os.path.realpath(os.getcwd())]
    return _file_roots_cache

# 敏感文件名黑名单（即使在允许根内也拒绝；fnmatch 匹配 basename）
SENSITIVE_FILENAME_PATTERNS = [
    ".opencode_secret", ".env", ".env.*", "id_rsa*", "id_ed25519*", "id_ecdsa*",
    "*.pem", "*.key", "*.p12", "*.pfx", "credentials.json", "secrets.*",
    ".netrc", "_netrc", ".aws", ".gnupg",
]
# 敏感相对路径后缀黑名单
SENSITIVE_PATH_SUFFIXES = [".git/config", ".ssh/authorized_keys", ".ssh/known_hosts"]

def _resolve_sandboxed_path(path: str):
    """解析并校验路径。返回 (real_path, None)；失败返回 (None, "PATH_NOT_ALLOWED: 原因")。
    防 .. 与符号链接绕行（realpath 后做 commonpath 包含判断）。"""
    import fnmatch
    expanded = os.path.expanduser(path or "")
    if not expanded:
        return None, "PATH_NOT_ALLOWED: 空路径"
    real = os.path.realpath(os.path.abspath(expanded))
    roots = _get_file_roots()
    try:
        allowed = any(os.path.commonpath([real, root]) == root for root in roots)
    except ValueError:
        allowed = False
    if not allowed:
        return None, f"PATH_NOT_ALLOWED: 路径超出允许范围（仅可访问: {', '.join(roots)}）"
    base = os.path.basename(real)
    for pat in SENSITIVE_FILENAME_PATTERNS:
        if fnmatch.fnmatch(base, pat):
            return None, f"PATH_NOT_ALLOWED: 敏感文件禁止访问 ({base})"
    rel_posix = real.replace(os.sep, "/")
    for suf in SENSITIVE_PATH_SUFFIXES:
        if rel_posix.endswith("/" + suf) or rel_posix.endswith(suf):
            return None, f"PATH_NOT_ALLOWED: 敏感路径禁止访问 ({suf})"
    return real, None

class PathNotAllowedError(PermissionError):
    pass

def _list_dir_entries(path: str) -> list:
    """列出目录条目：按目录优先、名称排序（沙盒校验在前）。"""
    real_path, err = _resolve_sandboxed_path(path)
    if err:
        raise PathNotAllowedError(err)
    abs_path = real_path
    entries = []
    with os.scandir(abs_path) as it:
        for entry in it:
            try:
                is_dir = entry.is_dir(follow_symlinks=False)
                stat = entry.stat(follow_symlinks=False)
                entries.append({
                    "name": entry.name,
                    "is_dir": is_dir,
                    "size": stat.st_size,
                    "mtime": int(stat.st_mtime),
                })
            except OSError:
                continue
    entries.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
    return entries

def _read_text_file(path: str) -> tuple:
    """读取文本文件；二进制或超大文件拒绝/截断。返回 (content, truncated)。"""
    real_path, err = _resolve_sandboxed_path(path)
    if err:
        raise PathNotAllowedError(err)
    abs_path = real_path
    if not os.path.isfile(abs_path):
        raise FileNotFoundError(f"不是文件: {abs_path}")
    size = os.path.getsize(abs_path)
    truncated = size > config.FILE_READ_MAX_BYTES
    with open(abs_path, "rb") as f:
        raw = f.read(config.FILE_READ_MAX_BYTES)
    if b"\x00" in raw:
        raise ValueError("二进制文件不支持预览")
    # 尝试 utf-8，失败则用系统默认编码容错
    try:
        content = raw.decode("utf-8")
    except UnicodeDecodeError:
        content = raw.decode("utf-8", errors="replace")
    return content, truncated


# v2.3: 写操作幂等——client_msg_id 去重缓存（TTL 10 分钟，防断线重发导致重复任务）
_client_msg_id_seen: dict = {}
_CLIENT_MSG_ID_TTL = 600

def _is_duplicate_client_msg(client_msg_id: str) -> bool:
    """client_msg_id 见过且未过期则返回 True（重复），否则记录并返回 False。"""
    if not client_msg_id:
        return False
    now = time.time()
    # 顺手清理过期条目（低频操作，直接全扫）
    expired = [k for k, ts in _client_msg_id_seen.items() if now - ts > _CLIENT_MSG_ID_TTL]
    for k in expired:
        del _client_msg_id_seen[k]
    if client_msg_id in _client_msg_id_seen:
        return True
    _client_msg_id_seen[client_msg_id] = now
    return False

