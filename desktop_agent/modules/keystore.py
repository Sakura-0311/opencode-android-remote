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

# v4.7.0/A5: 只在调用方还没配过 handler 时给默认配置（import 时不改写 root logger）
if not logging.getLogger().handlers:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] [DesktopAgent] %(message)s"
    )
logger = logging.getLogger("DesktopAgent")
from modules import config
def _ensure_secret_dir():
    try:
        os.makedirs(config._DEFAULT_SECRET_DIR, mode=0o700, exist_ok=True)
        os.chmod(config._DEFAULT_SECRET_DIR, 0o700)
    except Exception:
        pass

def _migrate_legacy_secret() -> None:
    """旧位置（启动目录 .opencode_secret）有有效 secret 且新位置没有时，迁移过去。"""
    if config.SECRET_FILE_PATH != config._DEFAULT_SECRET_PATH:
        return  # 用户自定义了位置，不碰
    if os.path.exists(config._DEFAULT_SECRET_PATH):
        return
    if not os.path.exists(config._LEGACY_SECRET_PATH):
        return
    try:
        with open(config._LEGACY_SECRET_PATH, "r", encoding="utf-8") as f:
            old_key = f.read().strip()
        if old_key and len(old_key) >= 16:
            _ensure_secret_dir()
            fd = os.open(config._DEFAULT_SECRET_PATH, os.O_WRONLY | os.O_CREAT | os.O_EXCL, stat.S_IRUSR | stat.S_IWUSR)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(old_key)
            logger.info("[v2.2.1-A] 主 Secret 已从旧位置迁移到 %s", config._DEFAULT_SECRET_PATH)
    except Exception as e:
        logger.warning(f"[v2.2.1-A] 迁移旧 Secret 失败: {e}")

# ==============================================================================
# v4.2.0/V2: 系统级密钥存储（Windows Credential Manager / macOS Keychain）
# keyring 为可选依赖：import 失败或后端不可用时，一律回退原有 0600 文件存储
# ==============================================================================
_KEYRING_SERVICE = "opencode-remote"
_KEYRING_ACCOUNT = "pairing-secret"

def _keyring_get(account: str = _KEYRING_ACCOUNT) -> Optional[str]:
    """从 OS 密钥库读取配对密钥。无 keyring 依赖或后端时返回 None。"""
    try:
        import keyring
    except ImportError:
        return None
    try:
        stored = keyring.get_password(_KEYRING_SERVICE, account)
        if stored and len(stored) >= 16:
            return stored
    except Exception as e:
        logger.debug(f"keyring read failed, fallback to file: {e}")
    return None

def _keyring_save(new_value: str, account: str = _KEYRING_ACCOUNT) -> bool:
    """写入 OS 密钥库。成功返回 True，无依赖/后端时返回 False。"""
    try:
        import keyring
    except ImportError:
        return False
    try:
        keyring.set_password(_KEYRING_SERVICE, account, new_value)
        return True
    except Exception as e:
        logger.debug(f"keyring write failed, fallback to file: {e}")
        return False

# ==============================================================================
# P0-1: 本地密钥管理（0600 受限权限，内存保管，绝不打日志）
# ==============================================================================
# v4.7.0/P1-7: 本次调用是否新生成了 secret（控制启动 banner 是否打印明文）
_secret_just_generated = False


def was_secret_just_generated() -> bool:
    """本次进程启动是否新生成了 secret（供 banner 决定是否打印明文）。"""
    return _secret_just_generated


def get_or_create_secret() -> str:
    """获取或初始化持久化配对 Secret，确保权限仅当前用户可读写 (0600)"""
    global _secret_just_generated
    _secret_just_generated = False
    # v4.2.0/V2: 优先系统密钥库（Windows Credential Manager / macOS Keychain）
    stored = _keyring_get()
    if stored:
        return stored
    if config.SECRET_FILE_PATH == config._DEFAULT_SECRET_PATH:
        _ensure_secret_dir()
        _migrate_legacy_secret()
    if os.path.exists(config.SECRET_FILE_PATH):
        try:
            with open(config.SECRET_FILE_PATH, "r", encoding="utf-8") as f:
                secret = f.read().strip()
                if secret and len(secret) >= 16:
                    return secret
        except Exception as e:
            logger.warning(f"Failed to read existing secret file: {e}")

    # 生成 128 bit 高熵随机十六进制密钥（32 hex 字符）
    new_secret = secrets.token_hex(16)
    _secret_just_generated = True
    # v4.2.0/V2: 先尝试系统密钥库，成功则不再落盘文件（文件作为回退）
    try:
        if _keyring_save(new_secret):
            logger.info("pairing secret stored in OS keyring (Credential Manager/Keychain)")
            return new_secret
    except Exception:
        pass
    try:
        fd = os.open(config.SECRET_FILE_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(new_secret)
        logger.info(f"Initialized secure pairing secret in {config.SECRET_FILE_PATH} (mode 0600)")
    except Exception as e:
        logger.warning(f"Could not write secret to disk ({e}); using in-memory secret.")

    return new_secret

# ==============================================================================
# P2-9: 终端配对信息渲染
# ==============================================================================
def print_pairing_banner(account_id: str, secret: str, relay_url: str,
                        show_secret: bool = False):
    """v4.7.0/P1-7: 默认不再打印明文 secret（会进 systemd/journald 日志）；
    仅首次生成或显式 --show-secret 时打印。"""
    pairing_payload = json.dumps({
        "account_id": account_id,
        "relay_url": relay_url
    })

    print("\n" + "=" * 64)
    print("  🚀 OpenCode Desktop Bridge Agent (v1.4 - Real Protocol Edition)")
    print("=" * 64)
    print(f"  🔑 Account ID (房间名):      \033[1;36m{account_id}\033[0m")
    if show_secret:
        print(f"  🔒 Secret (访问鉴权密钥):    \033[1;32m{secret}\033[0m  (严禁泄露给未授权第三方)")
    else:
        print("  🔒 Secret:                   ********（已保存；查看用 pair --show-secret）")
    print(f"  🌐 Relay Server URL:         {relay_url}")
    print(f"  🤖 Local OpenCode Target:     {config.OPENCODE_API_URL}")
    if config.OPENCODE_PASSWORD:
        print(f"  🔐 OpenCode Basic Auth:      已启用密码认证保护")
    print("=" * 64)

    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(pairing_payload)
        print("  📱 手机扫码快速填入配对地址 (不含 Secret，Secret 可使用 App 一键粘贴)：\n")
        qr.print_ascii(invert=True)
    except ImportError:
        print("  💡 提示: 安装 qrcode 库可在终端直接打印扫码二维码: pip install qrcode")
    
    print("=" * 64)
    print("  👉 在手机端 App 输入上述【账号】并在 Secret 栏点击粘贴即可安全连接！\n")

# ==============================================================================
# 任务跟踪管理
