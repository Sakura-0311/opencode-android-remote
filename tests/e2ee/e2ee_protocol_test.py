#!/usr/bin/env python3
"""
E2EE 协议级联调（同步版）— 用 websocket-client，不依赖 websockets 异步库。
流程：relay + mock desktop + mock mobile 全在 Python 里跑，
验证配对/HMAC/密钥交换/加解密全链路。
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import urllib.request

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

RELAY_WS = "ws://127.0.0.1:8765"
COORD = "http://127.0.0.1:8080"
ACCOUNT = "test"

def b64e(b: bytes) -> str: return base64.b64encode(b).decode()
def b64d(s: str) -> bytes: return base64.b64decode(s)

def sign_pubkey(master_secret: str, device_id: str, pubkey_b64: str) -> str:
    # 与 desktop_agent/modules/e2ee.py::sign_pubkey 和 Kotlin E2eeCrypto.hmacPubkeySig 一致
    msg = f"e2ee-pubkey|{device_id}|{pubkey_b64}".encode()
    return hmac.new(master_secret.encode(), msg, hashlib.sha256).hexdigest()

def derive_keys(shared: bytes, salt: bytes):
    d2m = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"opencode-e2ee-d2m").derive(shared)
    m2d = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"opencode-e2ee-m2d").derive(shared)
    return d2m, m2d

def encrypt(key: bytes, plaintext: str, sender_id: str, session_id: str) -> str:
    import os
    aead = ChaCha20Poly1305(key)
    nonce = os.urandom(12)
    aad = f"{sender_id}|{session_id}".encode()
    ct = aead.encrypt(nonce, plaintext.encode(), aad)
    return b64e(nonce + ct)

def decrypt(key: bytes, enc_b64: str, sender_id: str, session_id: str) -> str:
    aead = ChaCha20Poly1305(key)
    raw = b64d(enc_b64)
    nonce, ct = raw[:12], raw[12:]
    aad = f"{sender_id}|{session_id}".encode()
    return aead.decrypt(nonce, ct, aad).decode()

def http_get(path: str) -> str:
    with urllib.request.urlopen(COORD + path, timeout=10) as r:
        return r.read().decode()

def main():
    import websocket
    websocket.enableTrace(False)

    master_secret = "testsecret"  # 与 relay 测试配置一致

    # --- Desktop 侧 ---
    desk_priv = X25519PrivateKey.generate()
    desk_pub_b64 = b64e(desk_priv.public_key().public_bytes_raw())
    desk_sig = sign_pubkey(master_secret, "mock-desktop-1", desk_pub_b64)

    ws_url_d = f"{RELAY_WS}/ws"
    print(f"[test] connecting desktop to {ws_url_d}", flush=True)
    ws_d = None
    for i in range(5):
        try:
            ws_d = websocket.create_connection(ws_url_d, timeout=10)
            break
        except Exception as e:
            print(f"[test] desktop connect attempt {i+1} failed: {e}", flush=True)
            time.sleep(3)
    if not ws_d:
        print("E2EE_FAIL: desktop WS connect failed"); sys.exit(1)
    ws_d.send(json.dumps({"type": "hello", "v": 4, "capabilities": ["e2ee"], "device_id": "mock-desktop-1"}))
    assert json.loads(ws_d.recv())["type"] == "hello_ack", "desktop hello failed"
    ws_d.send(json.dumps({"type": "auth", "account_id": ACCOUNT, "secret": "testsecret",
                          "client_type": "desktop", "device_id": "mock-desktop-1"}))
    r = json.loads(ws_d.recv())
    assert r.get("type") != "auth_error", f"desktop auth failed: {r}"
    ws_d.send(json.dumps({"type": "create_pairing", "desktop_name": "MockDesktop",
                          "e2ee_pubkey": desk_pub_b64, "e2ee_pubkey_sig": desk_sig}))
    r = json.loads(ws_d.recv())
    assert r.get("type") == "pairing_created", f"pairing failed: {r}"
    token = r["pairing_token"]
    print(f"[test] desktop pairing created", flush=True)

    # --- Mobile 侧 ---
    mob_priv = X25519PrivateKey.generate()
    mob_pub_b64 = b64e(mob_priv.public_key().public_bytes_raw())

    ws_m = websocket.create_connection(f"{RELAY_WS}/ws", timeout=10)
    ws_m.send(json.dumps({"type": "pair_claim", "pairing_token": token, "account_id": ACCOUNT,
                          "device_name": "MockMobile", "e2ee_pubkey": mob_pub_b64}))
    r = json.loads(ws_m.recv())
    assert r.get("type") == "pair_success", f"pair_claim failed: {r}"
    device_secret = r["device_secret"]
    # HMAC 验签（手动配对模式）
    exp_sig = sign_pubkey(master_secret, "mock-desktop-1", r["e2ee_pubkey"])
    assert hmac.compare_digest(exp_sig, r.get("e2ee_pubkey_sig", "")), "HMAC sig mismatch"
    print(f"[test] mobile paired, HMAC verified", flush=True)

    # Desktop 收到 device_paired（含 mobile 公钥，跳过 ping）
    ws_d.settimeout(30)
    r = None
    for _ in range(10):
        r = json.loads(ws_d.recv())
        if r.get("type") == "ping":
            ws_d.send(json.dumps({"type": "pong"}))
            continue
        break
    assert r.get("type") == "device_paired", f"expected device_paired: {r}"
    mob_pub_recv = r["e2ee_pubkey"]
    assert mob_pub_recv == mob_pub_b64, "mobile pubkey mismatch"

    # 双方派生密钥（应一致）
    salt = hashlib.sha256(b64d(desk_pub_b64) + b64d(mob_pub_b64)).digest()
    d2m_d, m2d_d = derive_keys(desk_priv.exchange(X25519PublicKey.from_public_bytes(b64d(mob_pub_b64))), salt)
    d2m_m, m2d_m = derive_keys(mob_priv.exchange(X25519PublicKey.from_public_bytes(b64d(desk_pub_b64))), salt)
    assert d2m_d == d2m_m and m2d_d == m2d_m, "key derivation mismatch"
    print(f"[test] keys derived consistently", flush=True)

    # Mobile auth 后发送 E2EE 消息
    ws_m.close()
    ws_m = websocket.create_connection(f"{RELAY_WS}/ws", timeout=10)
    ws_m.send(json.dumps({"type": "auth", "account_id": ACCOUNT, "secret": device_secret,
                          "client_type": "mobile", "device_id": "mock-mobile-1"}))
    # auth 后 relay 会发 auth_ok + seq_sync（可能还有 resync_required），全部读掉
    ws_m.settimeout(10)
    for _ in range(5):
        r = json.loads(ws_m.recv())
        if r.get("type") == "auth_error":
            print(f"E2EE_FAIL: mobile auth failed: {r}"); sys.exit(1)
        if r.get("type") == "seq_sync":
            break
    print(f"[test] mobile authenticated", flush=True)

    session_id = "test-session-1"
    plaintext = "hello e2ee integration test"
    enc = encrypt(m2d_m, plaintext, "mock-mobile-1", session_id)
    ws_m.send(json.dumps({"action": "send_prompt",
                          "session_id": session_id, "e2ee": True,
                          "encrypted_payload": enc, "sender_id": "mock-mobile-1"}))
    print(f"[test] mobile sent E2EE prompt", flush=True)
    # 检查 mobile 是否收到错误回包（如 DESKTOP_OFFLINE）
    ws_m.settimeout(5)
    try:
        err = json.loads(ws_m.recv())
        print(f"[test] mobile got response: {err}", flush=True)
    except Exception as e:
        print(f"[test] mobile no immediate response (ok): {e}", flush=True)

    # Desktop 收到并解密（跳过 relay 的 ping）
    ws_d.settimeout(30)
    r = None
    for _ in range(10):
        r = json.loads(ws_d.recv())
        if r.get("type") == "ping":
            ws_d.send(json.dumps({"type": "pong"}))
            continue
        break
    assert r.get("action") == "send_prompt" or r.get("type") == "send_prompt", f"unexpected: {r}"
    dec = decrypt(m2d_d, r["encrypted_payload"], "mock-mobile-1", session_id)
    assert "hello e2ee" in dec, f"decrypt mismatch: {dec}"
    print("E2EE_PASS: full protocol round-trip OK", flush=True)



if __name__ == "__main__":
    main()
