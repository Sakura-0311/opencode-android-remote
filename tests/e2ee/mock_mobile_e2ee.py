#!/usr/bin/env python3
"""
Mock mobile (Python) — mirrors the Kotlin E2eeManager/E2eeCrypto logic.
Used for protocol-level E2EE integration testing without Android emulator.

Flow:
1. Get pairing token from host coordinator (http://127.0.0.1:8080/token)
2. Generate X25519 keypair, claim pairing with mobile pubkey
3. Verify desktop pubkey HMAC signature (manual pairing mode, master secret)
4. Derive d2m/m2d keys via HKDF (same as Kotlin E2eeCrypto)
5. Connect to relay WS, auth, send E2EE-encrypted prompt
6. Mock desktop decrypts and writes E2EE_OK to coordinator
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import sys
import urllib.request

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

RELAY_WS = "ws://127.0.0.1:8765"
COORD = "http://127.0.0.1:8080"

def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()
def b64d(s: str) -> bytes:
    return base64.b64decode(s)

def sign_pubkey(master_secret: str, pubkey_b64: str) -> str:
    """Mirror of e2ee.py::sign_pubkey and Kotlin E2eeCrypto.hmacPubkeySig"""
    return hmac.new(master_secret.encode(), pubkey_b64.encode(), hashlib.sha256).hexdigest()

def derive_keys(shared: bytes, salt: bytes) -> tuple[bytes, bytes]:
    """Mirror of Kotlin E2eeCrypto.deriveKeys: d2m and m2d via HKDF-SHA256"""
    d2m = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"opencode-e2ee-d2m").derive(shared)
    m2d = HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=b"opencode-e2ee-m2d").derive(shared)
    return d2m, m2d

def encrypt(key: bytes, plaintext: str, sender_id: str, session_id: str) -> str:
    """Mirror of Kotlin E2eeCrypto.encrypt: ChaCha20-Poly1305, AAD = sender|session"""
    aead = ChaCha20Poly1305(key)
    nonce = os.urandom(12)
    aad = f"{sender_id}|{session_id}".encode()
    ct = aead.encrypt(nonce, plaintext.encode(), aad)
    return b64e(nonce + ct)

def http_get(path: str) -> str:
    with urllib.request.urlopen(COORD + path, timeout=10) as r:
        return r.read().decode()

async def main():
    try:
        import websockets
    except ImportError:
        print("E2EE_FAIL: websockets not installed", flush=True)
        sys.exit(1)

    # 1. Get token and master secret from coordinator
    for _ in range(30):
        token = http_get("/token").strip()
        if token != "WAIT":
            break
        await asyncio.sleep(2)
    else:
        print("E2EE_FAIL: no token from coordinator", flush=True)
        sys.exit(1)

    master_secret = http_get("/secret").strip()
    print(f"[mobile] got token, master_secret len={len(master_secret)}", flush=True)

    # 2. Generate mobile keypair
    mobile_priv = X25519PrivateKey.generate()
    mobile_pub_b64 = b64e(mobile_priv.public_key().public_bytes_raw())

    # 3. Claim pairing via relay HTTP (pairing endpoint)
    # The relay exposes pairing via WebSocket; use a temp WS to claim
    async with websockets.connect(RELAY_WS) as ws:
        await ws.send(json.dumps({
            "type": "pair_claim",
            "pairing_token": token,
            "account_id": "test-account",
            "device_name": "Mock Mobile",
            "e2ee_pubkey": mobile_pub_b64,
        }))
        resp = json.loads(await ws.recv())
        if resp.get("type") != "pair_success":
            print(f"E2EE_FAIL: pairing claim failed: {resp}", flush=True)
            sys.exit(1)

        device_secret = resp["device_secret"]
        desktop_pubkey_b64 = resp.get("e2ee_pubkey")
        desktop_sig = resp.get("e2ee_pubkey_sig")
        print(f"[mobile] paired, device_secret len={len(device_secret)}", flush=True)

        if not desktop_pubkey_b64:
            print("E2EE_FAIL: desktop did not provide e2ee_pubkey", flush=True)
            sys.exit(1)

        # 4. Verify HMAC signature (manual pairing mode: master secret known)
        expected_sig = sign_pubkey(master_secret, desktop_pubkey_b64)
        if not hmac.compare_digest(expected_sig, desktop_sig or ""):
            print(f"E2EE_FAIL: HMAC sig mismatch", flush=True)
            sys.exit(1)
        print("[mobile] HMAC signature verified", flush=True)

        # 5. Derive keys
        desktop_pub = X25519PublicKey.from_public_bytes(b64d(desktop_pubkey_b64))
        shared = mobile_priv.exchange(desktop_pub)
        salt = hashlib.sha256(b64d(desktop_pubkey_b64) + b64d(mobile_pub_b64)).digest()
        d2m, m2d = derive_keys(shared, salt)
        print(f"[mobile] keys derived", flush=True)

        # 6. Auth to relay as mobile device (pair_claim 后需重连再 auth)
        await ws.close()
        async with websockets.connect(RELAY_WS) as ws2:
            ws = ws2
            await ws.send(json.dumps({
                "type": "auth",
                "account_id": "test-account",
                "client_type": "mobile",
                "secret": device_secret,
                "device_id": "mock-mobile-1",
            }))
        auth_resp = json.loads(await ws.recv())
        if auth_resp.get("type") != "auth_ok":
            print(f"E2EE_FAIL: mobile auth failed: {auth_resp}", flush=True)
            sys.exit(1)
        print("[mobile] authenticated", flush=True)

        # 7. Send E2EE-encrypted prompt to desktop
        session_id = "test-session-1"
        plaintext = "hello e2ee integration test"
        enc_b64 = encrypt(m2d, plaintext, "mock-mobile-1", session_id)
        await ws.send(json.dumps({
            "action": "send_prompt",
            "target_device_id": "mock-desktop-1",
            "session_id": session_id,
            "e2ee": True,
            "encrypted_payload": enc_b64,
            "sender_id": "mock-mobile-1",
        }))
        print("[mobile] E2EE prompt sent, waiting for desktop result...", flush=True)

        # 8. Poll coordinator for result
        for _ in range(60):
            await asyncio.sleep(2)
            result = http_get("/result").strip()
            if result == "E2EE_OK":
                print("E2EE_PASS: desktop decrypted correctly", flush=True)
                sys.exit(0)
            elif result.startswith("E2EE_FAIL"):
                print(f"{result}", flush=True)
                sys.exit(1)
        print("E2EE_FAIL: timeout waiting for desktop", flush=True)
        sys.exit(1)

if __name__ == "__main__":
    asyncio.run(main())
