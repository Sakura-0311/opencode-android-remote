#!/usr/bin/env python3
"""E2EE 跨语言测试向量生成器（v4.1）。

生成 Python（cryptography）加密、Kotlin（Tink）解密的固定测试向量，
用于 android_app E2eeCryptoTest.`interop - decrypt Python-produced ciphertext`。

固定密钥/nonce 仅测试用，切勿用于生产。重新生成后需同步更新 Kotlin 测试中的硬编码向量。
"""
import base64
import json
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import e2ee
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

mobile_priv = bytes.fromhex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
desktop_priv = bytes.fromhex("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")
desktop_pub = X25519PrivateKey.from_private_bytes(desktop_priv).public_key().public_bytes_raw()

shared = X25519PrivateKey.from_private_bytes(desktop_priv).exchange(
    X25519PrivateKey.from_private_bytes(mobile_priv).public_key())
_, k_d2m = e2ee.derive_msg_keys(shared)

sender, session = "desk-test-1", "sess-test-1"
plaintext = '{"chunk":"Hello E2EE interop"}'
aad = f"{sender}:{session}".encode()
nonce = bytes.fromhex("000102030405060708090a0b")  # 固定，仅测试向量
ct = ChaCha20Poly1305(k_d2m).encrypt(nonce, plaintext.encode(), aad)

vec = {
    "mobile_priv_b64": base64.b64encode(mobile_priv).decode(),
    "desktop_pub_b64": base64.b64encode(desktop_pub).decode(),
    "sender_device_id": sender,
    "session_id": session,
    "plaintext": plaintext,
    "payload_b64": base64.b64encode(nonce + ct).decode(),
}
print(json.dumps(vec, indent=1))
