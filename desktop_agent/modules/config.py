"""v2.6: agent 配置常量。环境变量默认值；CLI 参数可覆盖（apply_cli_overrides）。"""
import os

from opencode_api import DEFAULT_OPENCODE_BASE_URL

RELAY_SERVER_URL = os.getenv("RELAY_SERVER_URL", "ws://127.0.0.1:8765")
RELAY_ADMIN_TOKEN = os.getenv("RELAY_ADMIN_TOKEN", "")
OPENCODE_API_URL = os.getenv("OPENCODE_API_URL", DEFAULT_OPENCODE_BASE_URL)
OPENCODE_PASSWORD = os.getenv("OPENCODE_PASSWORD", "")
DEFAULT_ACCOUNT_ID = os.getenv("OPENCODE_ACCOUNT_ID", "user_dev_001")
# v2.2.1-A: 主 Secret 默认移到 ~/.config/opencode-remote/（目录 0700 / 文件 0600）
_DEFAULT_SECRET_DIR = os.path.join(os.path.expanduser("~"), ".config", "opencode-remote")
_DEFAULT_SECRET_PATH = os.path.join(_DEFAULT_SECRET_DIR, ".opencode_secret")
_LEGACY_SECRET_PATH = os.path.abspath(".opencode_secret")
SECRET_FILE_PATH = os.getenv("OPENCODE_SECRET_FILE", _DEFAULT_SECRET_PATH)
SSE_CURSOR_FILE = os.getenv("OPENCODE_SSE_CURSOR_FILE", ".opencode_sse_cursor")
KNOWN_SESSION_TTL_SEC = float(os.getenv("AGENT_KNOWN_SESSION_TTL_SEC", "3600"))
FILE_READ_MAX_BYTES = int(os.getenv("AGENT_FILE_READ_MAX_BYTES", str(200 * 1024)))


def apply_cli_overrides(args):
    """v2.6: CLI 参数覆盖（优先级：CLI > 环境变量 > 默认值）。"""
    import sys as _sys
    mod = _sys.modules[__name__]
    if getattr(args, "relay_url", None):
        mod.RELAY_SERVER_URL = args.relay_url
    if getattr(args, "account_id", None):
        mod.DEFAULT_ACCOUNT_ID = args.account_id
    if getattr(args, "workspace", None):
        os.environ["AGENT_FILE_ROOTS"] = args.workspace
