"""E2EE（mobile <-> desktop，relay 盲转发）——desktop_agent 端实现。

线格式规范：docs/E2EE_WIRE_v1.md（严格遵守）
- 密钥协商：X25519 ECDH；共享密钥 -> HKDF-SHA256 方向隔离派生消息密钥
    - m2d: info=b"opencode-remote-e2ee-v1-m2d"（mobile -> desktop）
    - d2m: info=b"opencode-remote-e2ee-v1-d2m"（desktop -> mobile）
- 消息加密：ChaCha20-Poly1305（IETF，12 字节随机 nonce）
- AAD："{sender_device_id}:{session_id}" 的 UTF-8 字节（防跨会话重放）
- 载荷：base64(nonce(12B) || ciphertext) -> `encrypted_payload` 字段

密钥存储：
- 本机私钥：~/.config/opencode-remote/e2ee_privkey（0600，不存在则生成）
  可用 E2EE_CONFIG_DIR 环境变量覆盖配置目录（测试用）。
- 对端公钥：同目录 e2ee_peer_<device_id>.pub（base64，32 字节）

开关：默认关闭；E2EE_ENABLED=1 才启用。未启用 / 未协商 / 字段缺失时，
所有钩子静默返回原消息，旧明文流程不受影响。

仅依赖 `cryptography` 库。
"""
import base64
import binascii
import os
import stat

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)

# 方向隔离 info（与 docs/E2EE_WIRE_v1.md 严格一致）
_INFO_M2D = b"opencode-remote-e2ee-v1-m2d"
_INFO_D2M = b"opencode-remote-e2ee-v1-d2m"
_NONCE_LEN = 12


def is_enabled() -> bool:
    """E2EE 总开关（每次调用读环境变量，默认关闭）。"""
    return os.getenv("E2EE_ENABLED", "0") == "1"


def _config_dir() -> str:
    return os.getenv(
        "E2EE_CONFIG_DIR",
        os.path.join(os.path.expanduser("~"), ".config", "opencode-remote"),
    )


def _privkey_path() -> str:
    return os.path.join(_config_dir(), "e2ee_privkey")


def _peer_pubkey_path(device_id: str) -> str:
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in device_id)[:64]
    return os.path.join(_config_dir(), f"e2ee_peer_{safe}.pub")


def _ensure_config_dir() -> str:
    d = _config_dir()
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


# ---------------------------------------------------------------------------
# 密钥对与共享密钥
# ---------------------------------------------------------------------------

def generate_keypair() -> tuple:
    """生成 X25519 密钥对。返回 (priv_raw_32B, pub_raw_32B)。"""
    priv = X25519PrivateKey.generate()
    priv_raw = priv.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
    pub_raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv_raw, pub_raw


def _atomic_write_0600(path: str, data: str) -> None:
    """v4.7.0/A4: 原子创建 0600 文件——os.open 一步到位，无宽权限窗口。"""
    _ensure_config_dir()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
    except BaseException:
        try:
            os.close(fd)
        except OSError:
            pass
        raise


def get_or_create_keypair() -> tuple:
    """读取或创建本机密钥对。返回 (priv_raw_32B, pub_b64)。
    v4.7.0/A4: 优先系统密钥库（keyring），回退 0600 文件（原子创建）。"""
    # 先试 keyring
    try:
        from modules.keystore import _keyring_get, _keyring_save
        stored = _keyring_get("e2ee-privkey")
        if stored:
            priv_raw = base64.b64decode(stored)
            if len(priv_raw) == 32:
                priv = X25519PrivateKey.from_private_bytes(priv_raw)
                pub_raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
                return priv_raw, base64.b64encode(pub_raw).decode("ascii")
    except Exception:
        pass
    path = _privkey_path()
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            priv_raw = base64.b64decode(f.read().strip())
        if len(priv_raw) != 32:
            raise ValueError(f"E2EE 私钥文件损坏（{path}），请删除后重试")
    else:
        priv_raw, _ = generate_keypair()
        _atomic_write_0600(path, base64.b64encode(priv_raw).decode("ascii"))
        try:
            from modules.keystore import _keyring_save as _ks
            _ks(base64.b64encode(priv_raw).decode("ascii"), "e2ee-privkey")
        except Exception:
            pass
    priv = X25519PrivateKey.from_private_bytes(priv_raw)
    pub_raw = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return priv_raw, base64.b64encode(pub_raw).decode("ascii")


def derive_shared_key(priv_raw: bytes, peer_pub_raw: bytes) -> bytes:
    """X25519 ECDH 共享密钥。输入均为 32 字节 raw。"""
    if len(priv_raw) != 32 or len(peer_pub_raw) != 32:
        raise ValueError("E2EE: X25519 密钥须为 32 字节")
    priv = X25519PrivateKey.from_private_bytes(priv_raw)
    pub = X25519PublicKey.from_public_bytes(peer_pub_raw)
    return priv.exchange(pub)


def derive_msg_keys(shared: bytes) -> tuple:
    """由共享密钥派生方向隔离的消息密钥。返回 (k_m2d, k_d2m)，各 32 字节。"""
    def _hkdf(info: bytes) -> bytes:
        return HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=b"\x00" * 32,  # 显式 32 零字节：与 Kotlin Tink 侧严格一致
            info=info,
        ).derive(shared)

    return _hkdf(_INFO_M2D), _hkdf(_INFO_D2M)


def derive_msg_keys_for_peer(peer_device_id: str) -> tuple:
    """便捷函数：读本机私钥 + 对端公钥，派生 (k_m2d, k_d2m)。对端公钥不存在则抛错。"""
    priv_raw, _ = get_or_create_keypair()
    peer_pub_raw = load_peer_pubkey(peer_device_id)
    if peer_pub_raw is None:
        raise KeyError(f"E2EE: 未找到对端 {peer_device_id} 的公钥")
    return derive_msg_keys(derive_shared_key(priv_raw, peer_pub_raw))


# ---------------------------------------------------------------------------
# 对端公钥持久化
# ---------------------------------------------------------------------------

def save_peer_pubkey(device_id: str, pub_b64: str) -> None:
    """保存对端 X25519 公钥（base64，32 字节）。格式错误抛 ValueError。"""
    try:
        pub_raw = base64.b64decode(pub_b64.strip())
    except (binascii.Error, ValueError) as e:
        raise ValueError(f"E2EE: 对端公钥 base64 非法: {e}")
    if len(pub_raw) != 32:
        raise ValueError(f"E2EE: 对端公钥须为 32 字节，实得 {len(pub_raw)}")
    # 预校验：可构造公钥对象
    X25519PublicKey.from_public_bytes(pub_raw)
    # v4.7.0/A4: 原子 0600 创建，无宽权限窗口
    _atomic_write_0600(_peer_pubkey_path(device_id),
                       base64.b64encode(pub_raw).decode("ascii"))


def load_peer_pubkey(device_id: str):
    """读取对端公钥，返回 32 字节 raw；不存在返回 None。"""
    path = _peer_pubkey_path(device_id)
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        pub_raw = base64.b64decode(f.read().strip())
    if len(pub_raw) != 32:
        raise ValueError(f"E2EE: 对端公钥文件损坏（{path}）")
    return pub_raw


def list_peer_ids() -> list:
    """列出已保存对端公钥的 device_id。"""
    d = _config_dir()
    if not os.path.isdir(d):
        return []
    ids = []
    for name in os.listdir(d):
        if name.startswith("e2ee_peer_") and name.endswith(".pub"):
            ids.append(name[len("e2ee_peer_"):-len(".pub")])
    return sorted(ids)


# ---------------------------------------------------------------------------
# 加解密原语（严格按线格式规范）
# ---------------------------------------------------------------------------

def _aad(sender_device_id: str, session_id: str) -> bytes:
    return f"{sender_device_id}:{session_id}".encode("utf-8")


def encrypt(plaintext: bytes, key: bytes, sender_device_id: str, session_id: str) -> str:
    """ChaCha20-Poly1305 加密。返回 base64(nonce(12B) || ciphertext)。"""
    if len(key) != 32:
        raise ValueError("E2EE: 消息密钥须为 32 字节")
    nonce = os.urandom(_NONCE_LEN)
    ct = ChaCha20Poly1305(key).encrypt(nonce, plaintext, _aad(sender_device_id, session_id))
    return base64.b64encode(nonce + ct).decode("ascii")


def decrypt(payload_b64: str, key: bytes, sender_device_id: str, session_id: str) -> bytes:
    """解密 base64(nonce || ct)。AAD/密钥/方向任一不对即抛 InvalidTag。"""
    if len(key) != 32:
        raise ValueError("E2EE: 消息密钥须为 32 字节")
    try:
        raw = base64.b64decode(payload_b64.strip())
    except (binascii.Error, ValueError) as e:
        raise ValueError(f"E2EE: encrypted_payload base64 非法: {e}")
    if len(raw) < _NONCE_LEN + 16:  # nonce + 最小 Poly1305 tag
        raise ValueError("E2EE: encrypted_payload 过短")
    nonce, ct = raw[:_NONCE_LEN], raw[_NONCE_LEN:]
    return ChaCha20Poly1305(key).decrypt(nonce, ct, _aad(sender_device_id, session_id))


# ---------------------------------------------------------------------------
# v4.6.0: 内层格式 v2（JSON 对象）+ 序号防重放
#   m2d 内层: {"action": "<action>", "payload": {...}, "seq": <int>}
#   d2m 内层: {"type": "<type>", ...原内容字段..., "seq": <int>}
# 序号按对端、按方向独立计数，落盘持久化（防重放旧密文指令）。
# AAD sender: m2d 用手机的 relay device_id（v4.6.0 起 relay 在 device_paired
# 里带上，手机加密时用同一值）；d2m 用 desktop device_id。
# ---------------------------------------------------------------------------

import json as _json


def _seq_path(peer_id: str, direction: str) -> str:
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in peer_id)[:64]
    return os.path.join(_config_dir(), f"e2ee_seq_{safe}_{direction}")


def _load_seq(peer_id: str, direction: str) -> int:
    try:
        with open(_seq_path(peer_id, direction), "r", encoding="utf-8") as f:
            return max(0, int(f.read().strip() or "0"))
    except (OSError, ValueError):
        return 0


def _save_seq(peer_id: str, direction: str, n: int) -> None:
    _ensure_config_dir()
    tmp = _seq_path(peer_id, direction) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(str(int(n)))
    os.chmod(tmp, 0o600)
    os.replace(tmp, _seq_path(peer_id, direction))


def _next_outgoing_seq(peer_id: str) -> int:
    """d2m 发送序号：单调递增，落盘。"""
    n = _load_seq(peer_id, "d2m") + 1
    _save_seq(peer_id, "d2m", n)
    return n


def _check_incoming_seq(peer_id: str, seq: int) -> bool:
    """m2d 接收序号：必须严格递增，否则视为重放/乱序，拒绝。"""
    last = _load_seq(peer_id, "m2d")
    if seq <= last:
        return False
    _save_seq(peer_id, "m2d", seq)
    return True


# v4.6.0 P1-3: fail-closed——已协商对端后，以下控制类消息必须带合法 e2ee 信封，
# 明文一律拒绝（防恶意 relay 注入指令 / 伪造审批）。
CONTROL_ACTIONS = frozenset({
    "send_prompt", "cancel", "tool_approval_response",
    "create_session", "file_list", "file_read",
})

# v4.6.0 P1-3: d2m 可加密的内容型消息（内层 JSON，敏感字段不再明文过 relay）
ENCRYPTABLE_D2M = frozenset({
    "stream_chunk", "tool_approval_request", "file_read_result", "file_list_result",
})


def has_negotiated_peers() -> bool:
    """E2EE 已启用且至少有一个对端完成公钥协商。"""
    return is_enabled() and bool(list_peer_ids())


def control_allowed(action: str, msg: dict) -> bool:
    """控制消息是否放行。未协商时沿旧流程；协商后必须有合法信封。"""
    if not has_negotiated_peers():
        return True
    if action not in CONTROL_ACTIONS:
        return True
    return bool(msg.get("_e2ee_ok"))

def sign_pubkey(secret: str, device_id: str, pubkey_b64: str) -> str:
    """v4.3 M-2: 用房间主 secret 对 E2EE 公钥做 HMAC-SHA256 绑定。

    relay 只存 sha256(secret) 从不存明文，因此无法伪造该签名；
    mobile 用同样的主 secret 校验（仅手动配对持有主 secret 时有效）。
    消息格式与 Kotlin 侧 E2eeCrypto.hmacPubkeySig 严格一致：
    key=UTF-8(secret), msg=UTF-8("e2ee-pubkey|{device_id}|{pubkey_b64}"), 输出 hex。
    """
    import hashlib
    import hmac as hmac_mod
    msg = f"e2ee-pubkey|{device_id}|{pubkey_b64}".encode("utf-8")
    return hmac_mod.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


def decrypt_incoming(msg: dict) -> dict:
    """收消息钩子（mobile -> desktop）。v4.6.0 内层格式 v2。

    msg 含 e2ee 信封（e2ee=true + encrypted_payload）且已启用时：
    遍历已知对端，用各自 k_m2d + AAD(sender=对端 relay device_id, session_id)
    试解密；成功则解析内层 JSON {"action","payload","seq"}，校验序号严格递增
    （防重放），把 action/payload 写回 msg 并置 _e2ee_ok=True。
    解密失败 / 内层非法 / 序号回退 → 置 _e2ee_failed（上层 fail-closed 拒绝），
    不再按旧流程处理。未启用 / 无信封时直接返回原 dict。
    """
    if not is_enabled() or not isinstance(msg, dict) or not msg.get("e2ee"):
        return msg
    payload_b64 = msg.get("encrypted_payload", "")
    session_id = str(msg.get("session_id", "default"))
    if not payload_b64:
        msg["_e2ee_failed"] = "empty payload"
        return msg
    for peer_id in list_peer_ids():
        try:
            k_m2d, _ = derive_msg_keys_for_peer(peer_id)
            plaintext = decrypt(payload_b64, k_m2d, peer_id, session_id)
        except (InvalidTag, ValueError, KeyError):
            continue
        except Exception:
            continue
        try:
            inner = _json.loads(plaintext.decode("utf-8"))
        except Exception:
            continue
        if not isinstance(inner, dict):
            continue
        seq = inner.get("seq")
        action = inner.get("action")
        payload = inner.get("payload")
        if not isinstance(seq, int) or seq <= 0:
            continue
        if not isinstance(action, str) or not action or not isinstance(payload, dict):
            continue
        # 解密成功即确认来自该对端：序号回退直接 fail-closed，不再试其他对端
        if not _check_incoming_seq(peer_id, seq):
            msg["_e2ee_failed"] = f"replay/old seq={seq}"
            return msg
        msg["action"] = action
        msg["payload"] = payload
        msg["_e2ee_peer"] = peer_id  # 备注解密来源对端（诊断用）
        msg["_e2ee_ok"] = True
        return msg
    msg["_e2ee_failed"] = "decrypt failed"
    return msg


def encrypt_outgoing(msg: dict, peer_device_id: str = None, sender_device_id: str = None) -> dict:
    """发消息钩子（desktop -> mobile）。v4.6.0 内层格式 v2。

    已启用、peer 明确（或仅有一个已协商对端）、msg 为内容型消息时：
    用 k_d2m + AAD(sender=本机 desktop device_id, session_id) 加密内层 JSON
    {"type":..., ...内容字段..., "seq":N}，替换为 e2ee 信封
    （保留 type / session_id / relay_seq / source_device_id 供 relay 路由）。
    否则原样返回。
    当前处理的内容型消息：stream_chunk、tool_approval_request、
    file_read_result、file_list_result。
    """
    if not is_enabled() or not isinstance(msg, dict):
        return msg
    peer = peer_device_id
    if peer is None:
        peers = list_peer_ids()
        if len(peers) != 1:
            return msg  # 对端不明（0 个或多个），不加密
        peer = peers[0]
    try:
        _, k_d2m = derive_msg_keys_for_peer(peer)
    except (KeyError, ValueError):
        return msg
    if sender_device_id is None:
        try:
            from modules.config import get_desktop_device_id
            sender_device_id = get_desktop_device_id()
        except Exception:
            sender_device_id = "desktop"
    session_id = str(msg.get("session_id", "default"))
    msg_type = msg.get("type")
    if msg_type not in ENCRYPTABLE_D2M:
        return msg

    inner = {"type": msg_type, "seq": _next_outgoing_seq(peer)}
    for k, v in msg.items():
        if k in ("type", "session_id", "relay_seq", "source_device_id",
                 "e2ee", "encrypted_payload"):
            continue
        inner[k] = v
    out = {
        "type": msg_type,
        "session_id": msg.get("session_id", "default"),
        "e2ee": True,
        "encrypted_payload": encrypt(
            _json.dumps(inner, ensure_ascii=False).encode("utf-8"),
            k_d2m, sender_device_id, session_id),
    }
    for k in ("relay_seq", "source_device_id"):
        if k in msg:
            out[k] = msg[k]
    return out
