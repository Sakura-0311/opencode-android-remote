#!/usr/bin/env python3
"""
B4: E2E 回声 desktop —— 模拟真实 desktop_agent 的最小协议子集。

流程: hello -> auth(建房) -> 收到 send_prompt -> 回 stream_start/chunk/end。
供 .github/workflows/e2e.yml 在 CI 模拟器里跑黄金路径测试用。
E2EE 默认关闭，走明文 payload（与 App 默认一致）。
"""
import asyncio
import json
import os
import sys

try:
    import websockets
except ImportError:
    sys.exit("需要 websockets 库: pip install websockets")

RELAY = os.environ.get("E2E_RELAY_URL", "ws://127.0.0.1:8765")
ACCOUNT = os.environ.get("E2E_ACCOUNT", "e2e-room")
SECRET = os.environ.get("E2E_SECRET", "e2e-secret")
ECHO_PREFIX = "E2E 回声："


async def main():
    url = f"{RELAY}/ws/{ACCOUNT}/desktop"
    print(f"[fake-desktop] 连接 {url}", flush=True)
    async with websockets.connect(url, max_size=8 * 1024 * 1024) as ws:
        # 1. hello
        await ws.send(json.dumps({
            "type": "hello", "v": 4,
            "capabilities": ["hello", "multi_desktop"],
            "device_id": "e2e-fake-desktop",
        }))
        ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        assert ack.get("type") == "hello_ack", f"hello 失败: {ack}"
        print(f"[fake-desktop] hello_ack v={ack.get('v')}", flush=True)

        # 2. auth（首次建房）
        await ws.send(json.dumps({
            "type": "auth", "account_id": ACCOUNT, "secret": SECRET,
            "client_type": "desktop", "device_id": "e2e-fake-desktop",
            "device_name": "E2E Fake Desktop",
        }))
        auth_resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
        if auth_resp.get("type") == "auth_error":
            sys.exit(f"[fake-desktop] auth 失败: {auth_resp}")
        print(f"[fake-desktop] auth 成功: {auth_resp.get('type')}", flush=True)

        # 3. 主循环：收到 send_prompt 就回声
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            if not isinstance(msg, dict):
                continue
            action = msg.get("action") or msg.get("type")
            if action == "send_prompt":
                sid = msg.get("session_id", "default")
                payload = msg.get("payload") or {}
                prompt = payload.get("prompt", "")
                print(f"[fake-desktop] 收到 prompt (sid={sid}): {prompt[:60]}", flush=True)
                await ws.send(json.dumps({"type": "stream_start", "session_id": sid}))
                await ws.send(json.dumps({
                    "type": "stream_chunk", "session_id": sid,
                    "chunk": f"{ECHO_PREFIX}{prompt}",
                }))
                await asyncio.sleep(0.3)
                await ws.send(json.dumps({"type": "stream_end", "session_id": sid}))
                print("[fake-desktop] 回声已发送", flush=True)
            elif action == "ping":
                await ws.send(json.dumps({"type": "pong"}))


if __name__ == "__main__":
    asyncio.run(main())
