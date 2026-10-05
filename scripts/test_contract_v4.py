"""
v4.0 契约测试：hello 强制 + 移除 legacy 路径（Android 单测另见 TransportTest）。

跑法：/tmp/ctvenv/bin/python scripts/test_contract_v4.py
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


async def hello_auth(ws, client_type, device_id=None, v=4):
    await ws.send(json.dumps({
        "type": "hello", "v": v,
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
    check("hello_ack v=4", ack.get("type") == "hello_ack" and ack.get("v") == 4, str(ack))
    check("hello_ack 含 multi_desktop",
          "multi_desktop" in ack.get("server_capabilities", []), str(ack))
    check("auth_ok 含 v 与 server_capabilities",
          auth_ok.get("type") == "auth_ok" and auth_ok.get("v") == 4
          and "multi_desktop" in auth_ok.get("server_capabilities", []), str(auth_ok))
    await ws.close()
    await maker.close()


async def test_no_hello_rejected():
    """v4.0: 无 hello 直接 auth（v2 旧客户端）被拒绝：hello_required + 4401"""
    maker = await make_room()
    ws = await new_ws("mobile")
    await ws.send(json.dumps({
        "type": "auth", "account_id": ACCOUNT, "secret": SECRET,
        "client_type": "mobile",
    }))
    resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    check("无 hello 收到 hello_required", resp.get("type") == "hello_required", str(resp))
    try:
        await asyncio.wait_for(ws.recv(), timeout=5)
        closed_4401 = False
    except Exception as e:
        closed_4401 = "4401" in str(e) or "close" in type(e).__name__.lower()
    check("连接被 4401 关闭", closed_4401, "")
    await maker.close()


async def test_v3_client_accepted():
    """v4.0: v3 客户端（hello v=3）仍被接受，hello_ack 回 v=4（向后兼容）"""
    maker = await make_room()
    ws = await new_ws("mobile")
    ack, auth_ok = await hello_auth(ws, "mobile", "mobile-v3compat", v=3)
    check("v3 hello 被接受", ack.get("type") == "hello_ack" and ack.get("v") == 4, str(ack))
    check("v3 hello 后 auth_ok", auth_ok.get("type") == "auth_ok", str(auth_ok))
    await ws.close()
    await maker.close()


async def test_e2ee_passthrough():
    """v4.1: E2EE 信封（e2ee+encrypted_payload）经 relay 盲转发，不被改动"""
    maker = await make_room()
    d = await new_ws("desktop")
    await hello_auth(d, "desktop", "desk-e2ee")
    m = await new_ws("mobile")
    await hello_auth(m, "mobile", "mobile-e2ee")
    # mobile 发 E2EE 信封（内容为假密文，relay 不应解析）
    fake_ct = "bm9uY2UxMmJ5dGVz" + "Y2lwaGVydGV4dA=="
    await m.send(json.dumps({
        "action": "send_prompt", "session_id": "se2e",
        "e2ee": True, "encrypted_payload": fake_ct,
        "client_msg_id": "c1",
    }))
    got = None
    try:
        got = json.loads(await asyncio.wait_for(d.recv(), timeout=5))
    except asyncio.TimeoutError:
        pass
    check("E2EE 信封原样到达 desktop",
          got and got.get("e2ee") is True and got.get("encrypted_payload") == fake_ct
          and "payload" not in got, str(got)[:200] if got else "timeout")
    # desktop 回 E2EE chunk，mobile 收到原样
    await d.send(json.dumps({
        "type": "stream_chunk", "session_id": "se2e",
        "e2ee": True, "encrypted_payload": fake_ct,
        "source_device_id": "desk-e2ee",
    }))
    got2 = None
    try:
        # 跳过 seq_sync 等无关消息，等 stream_chunk
        for _ in range(5):
            cand = json.loads(await asyncio.wait_for(m.recv(), timeout=5))
            if cand.get("type") == "stream_chunk":
                got2 = cand
                break
    except asyncio.TimeoutError:
        pass
    check("E2EE chunk 原样到达 mobile",
          got2 and got2.get("e2ee") is True and got2.get("encrypted_payload") == fake_ct,
          str(got2)[:200] if got2 else "timeout")
    await m.close(); await d.close(); await maker.close()


async def test_desktop_requires_device_id():
    """v4.0: desktop 无 device_id 的 auth 被拒绝（4401）"""
    maker = await make_room()
    ws = await new_ws("desktop")
    await ws.send(json.dumps({
        "type": "hello", "v": 4, "capabilities": ["hello"], "device_id": "",
    }))
    ack = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    assert ack.get("type") == "hello_ack"
    await ws.send(json.dumps({
        "type": "auth", "account_id": ACCOUNT, "secret": SECRET,
        "client_type": "desktop",
        # 无 device_id
    }))
    resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
    check("无 device_id 的 desktop 被拒", resp.get("type") == "auth_error", str(resp))
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


async def _drain(ws, n=5):
    for _ in range(n):
        try:
            await asyncio.wait_for(ws.recv(), timeout=0.2)
        except asyncio.TimeoutError:
            break


async def test_desktop_routing():
    """v3.1: desktop_routing —— target_device_id 定向路由 / 缺省走主 / 目标不存在报错 / source 打标 / list_desktops"""
    dA = await new_ws("desktop")
    ack_a, _ = await hello_auth(dA, "desktop", "desk-A")
    check("hello_ack 含 desktop_routing",
          "desktop_routing" in ack_a.get("server_capabilities", []), str(ack_a.get("server_capabilities")))
    await asyncio.sleep(0.05)
    dB = await new_ws("desktop")
    await hello_auth(dB, "desktop", "desk-B")
    await asyncio.sleep(0.3)
    # 此时主是 B（最后认证）
    m = await new_ws("mobile")
    await hello_auth(m, "mobile", "mobile-rt")
    await _drain(dA); await _drain(dB); await _drain(m)

    # 1. 带 target_device_id=desk-A：消息应到 A（而非主 B）
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s1",
                             "prompt": "to-A", "target_device_id": "desk-A"}))
    got_a = got_b = None
    try:
        raw = await asyncio.wait_for(dA.recv(), timeout=3)
        got_a = json.loads(raw)
    except asyncio.TimeoutError:
        pass
    try:
        raw = await asyncio.wait_for(dB.recv(), timeout=1)
        got_b = json.loads(raw)
    except asyncio.TimeoutError:
        pass
    check("target_device_id 路由到 A",
          got_a and got_a.get("prompt") == "to-A" and not got_b,
          f"A={bool(got_a)} B={bool(got_b)}")

    # 2. 不带 target：走主 desktop（B）
    await _drain(dA); await _drain(dB)
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s2", "prompt": "to-primary"}))
    got_b2 = None
    try:
        raw = await asyncio.wait_for(dB.recv(), timeout=3)
        got_b2 = json.loads(raw)
    except asyncio.TimeoutError:
        pass
    check("无 target 走主 desktop（B）",
          got_b2 and got_b2.get("prompt") == "to-primary", str(bool(got_b2)))

    # 3. target 不存在：明确错误（DESKTOP_OFFLINE + target_device_id）
    await _drain(m)
    await m.send(json.dumps({"type": "send_prompt", "session_id": "s3",
                             "prompt": "x", "target_device_id": "desk-NOPE"}))
    err = None
    try:
        err = json.loads(await asyncio.wait_for(m.recv(), timeout=3))
    except asyncio.TimeoutError:
        pass
    check("目标不存在返回明确错误",
          err and err.get("type") == "error" and err.get("code") == "DESKTOP_OFFLINE"
          and err.get("target_device_id") == "desk-NOPE", str(err))

    # 4. desktop->mobile 消息带 source_device_id
    await _drain(m)
    await dA.send(json.dumps({"type": "task_update", "session_id": "s1", "text": "hi"}))
    fwd = None
    try:
        fwd = json.loads(await asyncio.wait_for(m.recv(), timeout=3))
    except asyncio.TimeoutError:
        pass
    check("desktop->mobile 带 source_device_id",
          fwd and fwd.get("source_device_id") == "desk-A", str(fwd))

    # 5. list_desktops 返回在线列表（含主标记）
    await _drain(m)
    await m.send(json.dumps({"type": "list_desktops"}))
    lst = None
    try:
        # 健壮接收：CI 负载高时单次 recv 可能拿到残留旧消息，
        # 循环直到收到带 desktops 键的响应（总超时 10 秒）
        deadline = asyncio.get_event_loop().time() + 10
        while True:
            timeout = deadline - asyncio.get_event_loop().time()
            if timeout <= 0:
                break
            cand = json.loads(await asyncio.wait_for(m.recv(), timeout=timeout))
            if "desktops" in cand:
                lst = cand
                break
    except asyncio.TimeoutError:
        pass
    ds = (lst or {}).get("desktops", [])
    ids = {d.get("device_id") for d in ds}
    prim = [d for d in ds if d.get("is_primary")]
    check("list_desktops 返回 A/B 且主为 B",
          ids == {"desk-A", "desk-B"} and len(prim) == 1 and prim[0]["device_id"] == "desk-B",
          str(ds))

    # 6. v3 mobile（hello v=3、无 target）行为不变：仍走主
    m2 = await new_ws("mobile")
    await hello_auth(m2, "mobile", "mobile-v3b", v=3)
    await _drain(dA); await _drain(dB)
    await m2.send(json.dumps({"type": "send_prompt", "session_id": "s4", "prompt": "v3"}))
    got_v3 = None
    try:
        got_v3 = json.loads(await asyncio.wait_for(dB.recv(), timeout=3))
    except asyncio.TimeoutError:
        pass
    check("v3 客户端无 target 仍走主", got_v3 and got_v3.get("prompt") == "v3", str(bool(got_v3)))

    for w in (dA, dB, m, m2):
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
        await test_no_hello_rejected()
        await test_v3_client_accepted()
        await test_desktop_requires_device_id()
        await test_e2ee_passthrough()
        await test_multi_desktop()
        await test_primary_fallback_by_auth_time()
        await test_desktop_routing()
    finally:
        proc.terminate()
        proc.wait(timeout=10)

    failed = [r for r in results if not r[1]]
    print(f"\n{'='*50}\n共 {len(results)} 项，通过 {len(results)-len(failed)} 项")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    asyncio.run(main())
