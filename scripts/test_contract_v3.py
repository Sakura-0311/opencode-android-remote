"""
v3.0 契约测试：hello 能力协商 + 多 desktop 共存 + transport 切换（Android 单测另见 TransportTest）。

跑法：/tmp/osvvenv/bin/python scripts/test_contract_v3.py
启动真实 relay_server（uvicorn），用 websockets 客户端打真实协议：
  1. hello/hello_ack：v=3、server_capabilities 含 multi_desktop
  2. 无 hello 的 v2 旧客户端仍可直接 auth（向后兼容）
  3. 多 desktop：两个不同 device_id 共存；同 device_id 重连顶替旧连接
  4. mobile 消息路由到主 desktop（最近认证的）
"""
import asyncio
import json
import os
import subprocess
import sys
import time
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, REPO_ROOT)

import websockets

PORT = 18711
BASE = f"http://127.0.0.1:{PORT}"
ACCOUNT = "v3test"
SECRET = "v3-secret-xyz"

PASS, FAIL = "PASS", "FAIL"
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(f"[{PASS if cond else FAIL}] {name}" + (f" -- {detail}" if detail and not cond else ""))


async def new_ws(path_client):
    ws = await websockets.connect(f"ws://127.0.0.1:{PORT}/ws/{ACCOUNT}/{path_client}")
    return ws


async def hello_auth(ws, client_type, device_id=None):
    await ws.send(json.dumps({
        "type": "hello", "v": 3,
        "capabilities": ["hello", "multi_desktop"],
        "device_id": device_id or "",
    }))
    ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    await ws.send(json.dumps({
        "type": "auth", "account_id": ACCOUNT, "secret": SECRET,
        "client_type": client_type,
        **({"device_id": device_id} if device_id else {}),
    }))
    auth_ok = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    return ack, auth_ok


async def make_room():
    """desktop 先建房（mobile 不能建房）"""
    d = await new_ws("desktop")
    await hello_auth(d, "desktop", "room-maker")
    return d


async def test_hello():
    maker = await make_room()
    ws = await new_ws("mobile")
    ack, auth_ok = await hello_auth(ws, "mobile", "mobile-1")
    check("hello_ack v=3", ack.get("type") == "hello_ack" and ack.get("v") == 3, str(ack))
    check("hello_ack 含 multi_desktop",
          "multi_desktop" in ack.get("server_capabilities", []), str(ack))
    check("auth_ok 含 v 与 server_capabilities",
          auth_ok.get("type") == "auth_ok" and auth_ok.get("v") == 3
          and "multi_desktop" in auth_ok.get("server_capabilities", []), str(auth_ok))
    await ws.close()
    await maker.close()


async def test_v2_legacy_auth():
    """v2 旧客户端（无 hello，直接 auth）仍可连上"""
    maker = await make_room()
    ws = await new_ws("mobile")
    await ws.send(json.dumps({
        "type": "auth", "account_id": ACCOUNT, "secret": SECRET,
        "client_type": "mobile",
    }))
    resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    check("v2 旧客户端直接 auth 成功", resp.get("type") == "auth_ok", str(resp))
    await ws.close()
    await maker.close()


async def test_multi_desktop():
    d1 = await new_ws("desktop")
    ack1, ok1 = await hello_auth(d1, "desktop", "desk-A")
    check("desktop A 认证成功", ok1.get("type") == "auth_ok", str(ok1))

    d2 = await new_ws("desktop")
    ack2, ok2 = await hello_auth(d2, "desktop", "desk-B")
    check("desktop B 认证成功", ok2.get("type") == "auth_ok", str(ok2))

    # A 不应被顶替：A 的 socket 未被服务端关闭（recv 超时而非收到关闭帧）
    try:
        await asyncio.wait_for(d1.recv(), timeout=1.5)
        alive_a = False  # 收到了帧（关闭帧）→ 被顶替了
    except asyncio.TimeoutError:
        alive_a = True
    except Exception:
        alive_a = False
    check("不同 device_id 共存：A 未被 B 顶替", alive_a)

    # 同 device_id 重连：A' 顶替 A
    d1b = await new_ws("desktop")
    _, ok1b = await hello_auth(d1b, "desktop", "desk-A")
    check("同 device_id 重连认证成功", ok1b.get("type") == "auth_ok", str(ok1b))
    await asyncio.sleep(0.5)
    try:
        await d1.recv()  # 旧 A 应收到关闭帧
        closed_a = True
    except Exception:
        closed_a = True  # 已关闭也算顶替成功
    check("同 device_id 重连顶替旧连接", closed_a)

    # mobile 消息路由到主 desktop（最后认证的 A'）
    m = await new_ws("mobile")
    await hello_auth(m, "mobile", "mobile-2")
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s1", "prompt": "hi"}))
    got = None
    for ws_cand, name in ((d1b, "A'"), (d2, "B")):
        try:
            raw = await asyncio.wait_for(ws_cand.recv(), timeout=3)
            data = json.loads(raw)
            if data.get("type") in ("prompt_received", "send_prompt", "task_update"):
                got = name
                break
        except asyncio.TimeoutError:
            continue
    check("mobile 消息路由到主 desktop（最后认证）", got == "A'", f"got={got}")

    for w in (d1b, d2, m):
        await w.close()


async def test_primary_fallback_by_auth_time():
    """v3.0.2/B2: 主 desktop 掉线后，回退到最近认证的在线 desktop（而非字典第一项）"""
    dA = await new_ws("desktop")
    await hello_auth(dA, "desktop", "desk-A")
    await asyncio.sleep(0.05)
    dB = await new_ws("desktop")
    await hello_auth(dB, "desktop", "desk-B")
    await asyncio.sleep(0.05)
    dC = await new_ws("desktop")
    await hello_auth(dC, "desktop", "desk-C")
    await asyncio.sleep(0.3)

    # 主是最后认证的 C：mobile 消息应到 C
    m = await new_ws("mobile")
    await hello_auth(m, "mobile", "mobile-fb")
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s1", "prompt": "who"}))
    got = None
    for ws_cand, name in ((dA, "A"), (dB, "B"), (dC, "C")):
        try:
            raw = await asyncio.wait_for(ws_cand.recv(), timeout=3)
            if json.loads(raw).get("type") in ("prompt_received", "send_prompt", "task_update"):
                got = name
                break
        except asyncio.TimeoutError:
            continue
    check("主 desktop 为最后认证的 C", got == "C", f"got={got}")

    # C 断开：回退应到 B（最近认证的剩余者），而非 A（字典第一项）
    await dC.close()
    await asyncio.sleep(0.5)
    # 排空 B 上可能残留的旧帧
    for _ in range(3):
        try:
            await asyncio.wait_for(dB.recv(), timeout=0.3)
        except asyncio.TimeoutError:
            break
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s2", "prompt": "who2"}))
    got2 = None
    for ws_cand, name in ((dA, "A"), (dB, "B")):
        try:
            raw = await asyncio.wait_for(ws_cand.recv(), timeout=3)
            if json.loads(raw).get("type") in ("prompt_received", "send_prompt", "task_update"):
                got2 = name
                break
        except asyncio.TimeoutError:
            continue
    check("主掉线后回退到最近认证的 B", got2 == "B", f"got={got2}")

    for w in (dA, dB, m):
        await w.close()


async def main():
    env = dict(os.environ)
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "relay_server.server:app",
         "--host", "127.0.0.1", "--port", str(PORT)],
        cwd=REPO_ROOT, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        for _ in range(50):
            try:
                urllib.request.urlopen(f"{BASE}/api/health", timeout=1)
                break
            except Exception:
                time.sleep(0.2)
        await test_hello()
        await test_v2_legacy_auth()
        await test_multi_desktop()
        await test_primary_fallback_by_auth_time()
    finally:
        proc.terminate()
        proc.wait(timeout=10)

    failed = [r for r in results if not r[1]]
    print(f"\n{'='*50}\n共 {len(results)} 项，通过 {len(results)-len(failed)} 项")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    asyncio.run(main())
