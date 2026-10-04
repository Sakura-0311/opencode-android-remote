#!/usr/bin/env python3
"""
E2EE 联调 mock desktop（模拟器集成测试用）。

流程：
1. 连 relay，做 hello + auth（desktop）
2. create_pairing（带 E2EE 公钥 + HMAC 签名），输出 pairing_token 到 stdout
3. 等 device_paired，存 mobile 公钥
4. 等 send_prompt（e2ee=true），解密验证 plaintext
5. 输出 E2EE_OK / E2EE_FAIL

用法：
    python3 mock_desktop_e2ee.py --relay ws://127.0.0.1:8765 \
        --account test --secret testsecret --expect "hello e2ee"
"""
import argparse
import asyncio
import base64
import hashlib
import hmac as hmac_mod
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "desktop_agent", "modules"))
import e2ee as E


def sign_pubkey(secret: str, device_id: str, pubkey_b64: str) -> str:
    msg = f"e2ee-pubkey|{device_id}|{pubkey_b64}".encode("utf-8")
    return hmac_mod.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--relay", default="ws://127.0.0.1:8765")
    ap.add_argument("--account", default="test")
    ap.add_argument("--secret", default="testsecret")
    ap.add_argument("--expect", default="hello e2ee")
    ap.add_argument("--device-id", default="mock-desktop-1")
    args = ap.parse_args()

    import websockets

    # 独立密钥目录，避免污染真实 desktop 密钥
    os.environ["OPENCODE_REMOTE_CONFIG_DIR"] = "/tmp/mock-desktop-config"
    os.makedirs("/tmp/mock-desktop-config", exist_ok=True)

    priv_raw, pub_b64 = E.get_or_create_keypair()
    sig = sign_pubkey(args.secret, args.device_id, pub_b64)

    account = args.account
    ws_url = args.relay.rstrip("/") + f"/ws/{account}/desktop"
    print(f"[mock-desktop] connecting {ws_url}", flush=True)

    async with websockets.connect(ws_url, ping_interval=20, ping_timeout=10) as ws:
        # hello
        await ws.send(json.dumps({
            "type": "hello", "v": 4,
            "capabilities": ["e2ee"], "device_id": args.device_id,
        }))
        resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=8))
        assert resp.get("type") == "hello_ack", f"hello failed: {resp}"
        print("[mock-desktop] hello_ack OK", flush=True)

        # auth
        await ws.send(json.dumps({
            "type": "auth", "account_id": account, "secret": args.secret,
            "client_type": "desktop", "device_id": args.device_id,
        }))
        resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=8))
        assert resp.get("type") != "auth_error", f"auth failed: {resp}"
        print("[mock-desktop] auth OK", flush=True)

        # create_pairing
        await ws.send(json.dumps({
            "type": "create_pairing",
            "desktop_name": "MockDesktop",
            "e2ee_pubkey": pub_b64,
            "e2ee_pubkey_sig": sig,
        }))
        resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        assert resp.get("type") == "pairing_created", f"pairing failed: {resp}"
        token = resp["pairing_token"]
        # 把 token 写到文件，供测试驱动读取
        with open("/tmp/mock-pairing-token.txt", "w") as f:
            f.write(token)
        print(f"[mock-desktop] PAIRING_TOKEN={token}", flush=True)

        # 等 device_paired（mobile 扫码/认领）
        mobile_pub_b64 = None
        mobile_device_id = None
        try:
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=120))
                if msg.get("type") == "device_paired":
                    mobile_pub_b64 = msg.get("e2ee_pubkey")
                    mobile_device_id = msg.get("device_id") or "mock-mobile"
                    print(f"[mock-desktop] device_paired: {mobile_device_id}, "
                          f"mobile_pubkey={'yes' if mobile_pub_b64 else 'no'}", flush=True)
                    break
        except asyncio.TimeoutError:
            print("E2EE_FAIL: timeout waiting device_paired", flush=True)
            open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:timeout device_paired")
            sys.exit(1)

        if not mobile_pub_b64:
            print("E2EE_FAIL: mobile did not provide e2ee_pubkey", flush=True)
            open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:no mobile pubkey")
            sys.exit(1)

        # 派生密钥（desktop 视角：d2m 用于解密 mobile 发来的）
        peer_pub_raw = base64.b64decode(mobile_pub_b64)
        shared = E.derive_shared_key(priv_raw, peer_pub_raw)
        k_m2d, k_d2m = E.derive_msg_keys(shared)
        print("[mock-desktop] keys derived", flush=True)

        # 等 send_prompt，解密验证
        try:
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=180))
                if msg.get("type") != "send_prompt":
                    continue
                print(f"[mock-desktop] got send_prompt e2ee={msg.get('e2ee')}", flush=True)
                if not msg.get("e2ee"):
                    print("E2EE_FAIL: prompt not encrypted", flush=True)
                    open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:not encrypted")
                    sys.exit(1)
                enc_b64 = msg.get("encrypted_payload", "")
                if not enc_b64:
                    print("E2EE_FAIL: empty encrypted_payload", flush=True)
                    open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:empty payload")
                    sys.exit(1)
                # 用 m2d 解密（mobile→desktop 方向），AAD sender=mobile device id
                # 注意：desktop 的 m2d == mobile 的 m2d（同一方向密钥）
                pt_bytes = E.decrypt(enc_b64, k_m2d, mobile_device_id,
                                     msg.get("session_id", ""))
                plaintext = pt_bytes.decode("utf-8")
                print(f"[mock-desktop] decrypted: {plaintext[:80]}", flush=True)
                if args.expect in plaintext:
                    print("E2EE_OK", flush=True)
                    open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_OK")
                    sys.exit(0)
                else:
                    print(f"E2EE_FAIL: plaintext mismatch", flush=True)
                    open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:plaintext mismatch")
                    sys.exit(1)
        except asyncio.TimeoutError:
            print("E2EE_FAIL: timeout waiting send_prompt", flush=True)
            open("/tmp/mock-e2ee-result.txt", "w").write("E2EE_FAIL:timeout send_prompt")
            sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
