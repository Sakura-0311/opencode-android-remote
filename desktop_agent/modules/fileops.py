import logging
import os
import time

from modules import config

# import 时不直接 basicConfig（会改写整个进程的 root logger 配置）：
# 只有调用方还没配过 handler 时才给一个默认配置。
if not logging.getLogger().handlers:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] [DesktopAgent] %(message)s"
    )
logger = logging.getLogger("DesktopAgent")
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
    # 默认根为用户 home 或磁盘根时打印明确警告（沙盒范围过大）
    if not env_roots:
        home = os.path.realpath(os.path.expanduser("~"))
        for r in _file_roots_cache:
            if r == home or r == os.path.abspath(os.sep):
                logger.warning("文件浏览器根目录为 %s，沙盒范围过大，"
                               "建议用 AGENT_FILE_ROOTS 限定到项目目录", r)
    return _file_roots_cache

# 敏感文件名黑名单（即使在允许根内也拒绝；fnmatch 匹配 basename）
SENSITIVE_FILENAME_PATTERNS = [
    ".opencode_secret", ".env", ".env.*", "id_rsa*", "id_ed25519*", "id_ecdsa*",
    "*.pem", "*.key", "*.p12", "*.pfx", "credentials.json", "secrets.*",
    ".netrc", "_netrc", ".aws", ".gnupg",
    # 之前只匹配最终文件名，.aws/credentials 这类路径会被放行，在此补强
    "*.jks", "*.keystore", "keystore.properties", ".git-credentials",
    ".npmrc", ".pypirc", ".pgpass", "*.tfstate", "*.kdbx",
    "service-account*.json", "*.p8",
]
# 敏感目录组件黑名单（匹配 realpath 后的任一路径组件）
SENSITIVE_DIR_NAMES = {
    ".ssh", ".aws", ".gnupg", ".kube", ".docker", ".azure", "gcloud",
}
# 敏感相对路径后缀黑名单
SENSITIVE_PATH_SUFFIXES = [".git/config", ".ssh/authorized_keys", ".ssh/known_hosts"]

def _has_sensitive_component(real: str) -> bool:
    """realpath 后的路径中是否含有敏感目录组件。
    v4.7.0/A3: 大小写不敏感（macOS 文件系统默认不区分大小写，.SSH 可绕过）。"""
    import pathlib
    lowered = {p.lower() for p in SENSITIVE_DIR_NAMES}
    return any(p.lower() in lowered for p in pathlib.PurePath(real).parts)

def _is_sensitive_name(name: str) -> bool:
    """单条文件名/目录名是否命中黑名单（用于目录列表过滤）。
    v4.7.0/A3: 大小写不敏感。"""
    import fnmatch
    nl = name.lower()
    if nl in {p.lower() for p in SENSITIVE_DIR_NAMES}:
        return True
    return any(fnmatch.fnmatchcase(nl, pat.lower())
               for pat in SENSITIVE_FILENAME_PATTERNS)

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
    # v4.7.0/A3: 大小写不敏感（macOS 文件系统默认不区分，.ENV/ID_RSA 可绕过）
    base_l = base.lower()
    for pat in SENSITIVE_FILENAME_PATTERNS:
        if fnmatch.fnmatchcase(base_l, pat.lower()):
            return None, f"PATH_NOT_ALLOWED: 敏感文件禁止访问 ({base})"
    # 按路径组件拦截敏感目录（如 .aws/credentials 的 basename 是 credentials，
    # 原先只匹配 basename 会被放行）
    if _has_sensitive_component(real):
        return None, f"PATH_NOT_ALLOWED: 敏感目录禁止访问 ({real})"
    rel_posix = real.replace(os.sep, "/").lower()
    for suf in SENSITIVE_PATH_SUFFIXES:
        if rel_posix.endswith("/" + suf.lower()) or rel_posix.endswith(suf.lower()):
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
            # 目录列表同步过滤敏感条目，避免它们出现在手机端
            if _is_sensitive_name(entry.name):
                continue
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
    """读取文本文件；二进制或超大文件拒绝/截断。返回 (content, truncated)。
    v4.7.0/A3: O_NOFOLLOW 打开，防 read 时符号链接被替换（TOCTOU）。"""
    real_path, err = _resolve_sandboxed_path(path)
    if err:
        raise PathNotAllowedError(err)
    abs_path = real_path
    if not os.path.isfile(abs_path):
        raise FileNotFoundError(f"不是文件: {abs_path}")
    size = os.path.getsize(abs_path)
    truncated = size > config.FILE_READ_MAX_BYTES
    # O_NOFOLLOW: 若路径是符号链接直接失败（Windows 无此标志则跳过）
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(abs_path, flags)
    except OSError as e:
        raise PathNotAllowedError(f"PATH_NOT_ALLOWED: 无法安全打开 ({e})")
    with os.fdopen(fd, "rb") as f:
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

