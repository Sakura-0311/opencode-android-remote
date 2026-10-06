"""E2EE 自测：python3 desktop_agent/modules/test_e2ee.py

验证（全部按 docs/E2EE_WIRE_v1.md）：
 1. A/B 各生成密钥对 -> 双方推导的共享密钥一致
 2. m2d 加密 -> 用 k_d2m 解密失败（方向隔离）
 3. m2d 加密 -> 用 k_m2d 解密成功
 4. AAD 篡改（session_id / sender）-> 解密失败
 5. 密文篡改 -> 解密失败
 6. 私钥持久化 0600 + 对端公钥存取 roundtrip
 7. 线格式：公钥 32B、载荷 nonce(12B)||ct base64
"""
import base64
import json
import os
import stat
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 自测用临时配置目录，不碰真实 ~/.config
_tmp = tempfile.mkdtemp(prefix="e2ee_test_")
os.environ["E2EE_CONFIG_DIR"] = _tmp
os.environ["E2EE_ENABLED"] = "1"

from cryptography.exceptions import InvalidTag

import e2ee

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


# 1. 密钥对 + 共享密钥一致
priv_a, pub_a = e2ee.generate_keypair()
priv_b, pub_b = e2ee.generate_keypair()
check("公钥 32 字节", len(pub_a) == 32 and len(pub_b) == 32)
shared_a = e2ee.derive_shared_key(priv_a, pub_b)
shared_b = e2ee.derive_shared_key(priv_b, pub_a)
check("双方共享密钥一致", shared_a == shared_b and len(shared_a) == 32)

# 2. 方向隔离派生
k_m2d_a, k_d2m_a = e2ee.derive_msg_keys(shared_a)
k_m2d_b, k_d2m_b = e2ee.derive_msg_keys(shared_b)
check("m2d 密钥双方一致", k_m2d_a == k_m2d_b)
check("d2m 密钥双方一致", k_d2m_a == k_d2m_b)
check("m2d != d2m（方向隔离）", k_m2d_a != k_d2m_a)

# 3. m2d 加密 -> k_d2m 解密失败
ct = e2ee.encrypt("hello e2ee".encode(), k_m2d_a, "mobile-1", "s1")
check("载荷为 base64(nonce12B||ct)",
      len(base64.b64decode(ct)) > 12 + 16)
try:
    e2ee.decrypt(ct, k_d2m_b, "mobile-1", "s1")
    check("k_d2m 解 m2d 密文应失败", False)
except InvalidTag:
    check("k_d2m 解 m2d 密文失败（方向隔离）", True)

# 4. m2d 解密成功
pt = e2ee.decrypt(ct, k_m2d_b, "mobile-1", "s1")
check("k_m2d 解密成功", pt == "hello e2ee".encode())

# 5. AAD 篡改失败
for label, kw in [("session_id 篡改", {"session_id": "s2"}),
                  ("sender 篡改", {"sender_device_id": "mobile-2"})]:
    try:
        e2ee.decrypt(ct, k_m2d_b,
                     kw.get("sender_device_id", "mobile-1"),
                     kw.get("session_id", "s1"))
        check(f"{label}应失败", False)
    except InvalidTag:
        check(f"{label}解密失败", True)

# 6. 密文篡改失败
raw = bytearray(base64.b64decode(ct))
raw[20] ^= 0xFF
try:
    e2ee.decrypt(base64.b64encode(bytes(raw)).decode(), k_m2d_b, "mobile-1", "s1")
    check("密文篡改应失败", False)
except InvalidTag:
    check("密文篡改解密失败", True)

# 7. 私钥持久化：预置 B 的私钥作为本机密钥（模拟 desktop 端）
with open(os.path.join(_tmp, "e2ee_privkey"), "w", encoding="utf-8") as f:
    f.write(base64.b64encode(priv_b).decode("ascii"))
os.chmod(os.path.join(_tmp, "e2ee_privkey"), 0o600)
priv_raw, pub_b64 = e2ee.get_or_create_keypair()
mode = stat.S_IMODE(os.stat(os.path.join(_tmp, "e2ee_privkey")).st_mode)
# v5.0.1: POSIX 权限位在 Windows/NTFS 上不可表达（chmod 0600 无效），
# 仅在 POSIX 平台断言，避免 Windows 开发者本地永远红一条（CI 是 Ubuntu，覆盖仍在）
if os.name == "posix":
    check("私钥文件 0600", mode == 0o600)
else:
    print("[SKIP] 私钥文件 0600（Windows 无 POSIX 权限位，由 CI(Ubuntu) 覆盖）")
check("私钥读取一致（本机=B）", priv_raw == priv_b)
priv_raw2, pub_b64_2 = e2ee.get_or_create_keypair()
check("私钥持久化（二次读取一致）", priv_raw == priv_raw2 and pub_b64 == pub_b64_2)

# 8. 对端公钥存取
e2ee.save_peer_pubkey("mobile-1", base64.b64encode(pub_a).decode())
check("对端公钥 roundtrip", e2ee.load_peer_pubkey("mobile-1") == pub_a)
check("list_peer_ids", e2ee.list_peer_ids() == ["mobile-1"])
try:
    e2ee.save_peer_pubkey("bad", base64.b64encode(b"short").decode())
    check("非法公钥应拒绝", False)
except ValueError:
    check("非法公钥被拒绝", True)

# 9. 钩子：decrypt_incoming / encrypt_outgoing（v4.6.0 内层格式 v2）
inner_v2 = {"action": "send_prompt",
            "payload": {"prompt": "hello e2ee"}, "seq": 1}
ct_v2 = e2ee.encrypt(json.dumps(inner_v2).encode(), k_m2d_a, "mobile-1", "s1")
msg_in = {"action": "send_prompt", "session_id": "s1",
          "payload": {}, "e2ee": True, "encrypted_payload": ct_v2,
          "client_msg_id": "m1"}
# 注意：ct_v2 是用 k_m2d_a（mobile 方）加密的；decrypt_incoming 用对端 mobile-1 的 k_m2d 解密
out = e2ee.decrypt_incoming(dict(msg_in, payload=dict(msg_in["payload"])))
check("decrypt_incoming 还原 payload", out["payload"]["prompt"] == "hello e2ee"
      and out.get("_e2ee_ok") is True)

msg_out = {"type": "stream_chunk", "session_id": "s1", "chunk": "world"}
sealed = e2ee.encrypt_outgoing(msg_out, peer_device_id="mobile-1",
                               sender_device_id="desktop-1")
check("encrypt_outgoing 加信封", sealed.get("e2ee") is True
      and "encrypted_payload" in sealed and "chunk" not in sealed
      and sealed["session_id"] == "s1" and sealed["type"] == "stream_chunk")
# 用 d2m 解开验证（本机=B，对端=mobile-1）；v2 内层为 JSON
_, k_d2m_check = e2ee.derive_msg_keys(shared_b)
pt2 = e2ee.decrypt(sealed["encrypted_payload"], k_d2m_check, "desktop-1", "s1")
check("d2m 方向解密成功", json.loads(pt2)["chunk"] == "world")

# 10. 开关关闭时钩子透传
os.environ["E2EE_ENABLED"] = "0"
check("关闭时 decrypt_incoming 透传", e2ee.decrypt_incoming(msg_in) is msg_in)
check("关闭时 encrypt_outgoing 透传", e2ee.encrypt_outgoing(msg_out) is msg_out)
os.environ["E2EE_ENABLED"] = "1"

print(f"\n共 {len(PASS)} 项，全部通过")
