"""生成 E2EE 跨端互操作向量 tests/e2ee/interop_vectors.json。

用法: python3 tests/e2ee/gen_interop_vectors.py
输出: tests/e2ee/interop_vectors.json（提交进仓库，Kotlin 与 Python 单测共同读取）

向量内容（全部固定，可复现）:
- X25519 密钥对（mobile/desktop，base64 raw 32B）——写死，永不改变
- HKDF-SHA256（salt=32 零字节）派生的 k_m2d/k_d2m（hex）
- 固定 nonce（12 x 0x01）的加密向量：m2d/d2m 各一条
  - m2d 内层: {"action":"send_prompt","payload":{"prompt":"hello"},"seq":1}
  - d2m 内层: {"type":"stream_chunk","chunk":"hi","seq":1}
  - AAD: m2d sender="mob-relay-id-1", d2m sender="desk-relay-id-1", session="sess-1"
"""
import base64
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "..", "desktop_agent", "modules"))

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

import e2ee

# 写死的测试密钥对（仅测试用）
MOBILE_PRIV_B64 = "8Fyi3xKF80+ciqcxS+HcVhL1VU13wUh7A9E0/UnWA2M="
MOBILE_PUB_B64 = "oWNd5uVwLuUpDTeGDW6WwiCUbGri2f4DTQ7FWwDQzzU="
DESKTOP_PRIV_B64 = "eMigLnSO89VoP40xZGOYOiCOMfo+RNM1J7Dh1JtD8V8="
DESKTOP_PUB_B64 = "PMCcE/zOHzdKkMMwky6VRVm/Fw+P7TAqs5V3uOuGwkA="

MOBILE_ID = "mob-relay-id-1"      # 手机的 relay device_id（m2d AAD sender）
DESKTOP_ID = "desk-relay-id-1"   # desktop 的 device_id（d2m AAD sender）
SESSION_ID = "sess-1"
FIXED_NONCE = bytes([0x01] * 12)


def seal_fixed(plaintext: bytes, key: bytes, sender: str, session: str) -> str:
    aad = f"{sender}:{session}".encode("utf-8")
    ct = ChaCha20Poly1305(key).encrypt(FIXED_NONCE, plaintext, aad)
    return base64.b64encode(FIXED_NONCE + ct).decode("ascii")


def main():
    mob_priv = base64.b64decode(MOBILE_PRIV_B64)
    desk_priv = base64.b64decode(DESKTOP_PRIV_B64)
    mob_pub = base64.b64decode(MOBILE_PUB_B64)
    desk_pub = base64.b64decode(DESKTOP_PUB_B64)

    shared_m = e2ee.derive_shared_key(mob_priv, desk_pub)
    shared_d = e2ee.derive_shared_key(desk_priv, mob_pub)
    assert shared_m == shared_d, "ECDH 双方不一致"
    k_m2d, k_d2m = e2ee.derive_msg_keys(shared_m)

    m2d_inner = {"action": "send_prompt", "payload": {"prompt": "hello"}, "seq": 1}
    d2m_inner = {"type": "stream_chunk", "chunk": "hi", "seq": 1}
    m2d_pt = json.dumps(m2d_inner, ensure_ascii=False, sort_keys=True).encode("utf-8")
    d2m_pt = json.dumps(d2m_inner, ensure_ascii=False, sort_keys=True).encode("utf-8")

    vectors = {
        "version": 1,
        "note": "E2EE v4.6.0 内层格式 v2 互操作向量；nonce 固定仅用于测试",
        "mobile_priv_b64": MOBILE_PRIV_B64,
        "mobile_pub_b64": MOBILE_PUB_B64,
        "desktop_priv_b64": DESKTOP_PRIV_B64,
        "desktop_pub_b64": DESKTOP_PUB_B64,
        "k_m2d_hex": k_m2d.hex(),
        "k_d2m_hex": k_d2m.hex(),
        "hkdf_info_m2d": "opencode-remote-e2ee-v1-m2d",
        "hkdf_info_d2m": "opencode-remote-e2ee-v1-d2m",
        "aad_format": "{sender_device_id}:{session_id}",
        "vectors": [
            {
                "direction": "m2d",
                "sender_device_id": MOBILE_ID,
                "session_id": SESSION_ID,
                "inner_json": m2d_inner,
                "nonce_hex": FIXED_NONCE.hex(),
                "encrypted_payload_b64": seal_fixed(m2d_pt, k_m2d, MOBILE_ID, SESSION_ID),
            },
            {
                "direction": "d2m",
                "sender_device_id": DESKTOP_ID,
                "session_id": SESSION_ID,
                "inner_json": d2m_inner,
                "nonce_hex": FIXED_NONCE.hex(),
                "encrypted_payload_b64": seal_fixed(d2m_pt, k_d2m, DESKTOP_ID, SESSION_ID),
            },
        ],
    }

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "interop_vectors.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(vectors, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"已生成 {out}")

    # 自校验：用 e2ee.decrypt 解开向量
    for v in vectors["vectors"]:
        key = k_m2d if v["direction"] == "m2d" else k_d2m
        pt = e2ee.decrypt(v["encrypted_payload_b64"], key,
                          v["sender_device_id"], v["session_id"])
        assert json.loads(pt.decode("utf-8")) == v["inner_json"], v["direction"]
    print("自校验通过：向量可被 e2ee.decrypt 解开")


if __name__ == "__main__":
    main()
