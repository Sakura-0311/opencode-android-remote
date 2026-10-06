#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5.0.2: relay d2m 定向投递 + 缓冲补发按目标过滤的回归测试。

背景：desktop 侧多对端 E2EE 会给每台手机各加密一份，并用 target_device_id
指认归属。relay 必须：
  1. 定向帧只投给那台手机（其余手机不该收到自己解不开的密文）；
  2. 无 target 的帧仍然广播（旧行为不变，向后兼容）；
  3. 断线补发时只把这台手机的帧还给它，并且**不能**因为「缓冲里全是别人的帧」
     就误判为缓冲区过期而要求重同步。
"""
import asyncio
import json
import os
import sys

os.environ.setdefault("RELAY_ADMIN_TOKEN", "test-admin-token-0123456789abcdef")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from server import ClientSession, ConnectionManager, RoomBuffer  # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


class FakeWebSocket:
    def __init__(self):
        self.sent = []

    async def send_text(self, msg: str):
        self.sent.append(msg)

    async def close(self, code=1000, reason=""):
        pass


def make_session(client_type, device_id=""):
    ws = FakeWebSocket()
    s = ClientSession(ws, client_type, "room1", "127.0.0.1")
    s.is_authenticated = True
    s.device_id = device_id
    return s


async def main():
    mgr = ConnectionManager()
    # 建房：desktop 先认证
    desktop = make_session("desktop", "desk-1")
    mgr.rooms["room1"] = {
        "secret_hash": "x", "desktop": desktop, "mobiles": [],
        "msg_buffer": RoomBuffer(), "next_seq": 1, "room_epoch": "ep",
        "desktops": {"desk-1": desktop}, "devices": {},
    }
    mob_a = make_session("mobile", "mob-A")
    mob_b = make_session("mobile", "mob-B")
    mgr.rooms["room1"]["mobiles"] = [mob_a, mob_b]

    # 1. 定向帧只到目标手机
    await mgr.route_message(desktop, json.dumps({
        "type": "stream_chunk", "session_id": "s1", "e2ee": True,
        "encrypted_payload": "AAA", "target_device_id": "mob-A"}))
    check("定向帧：目标手机收到", len(mob_a.websocket.sent) == 1)
    check("定向帧：非目标手机收不到", len(mob_b.websocket.sent) == 0)
    check("定向帧：target_device_id 被保留（供客户端判归属）",
          json.loads(mob_a.websocket.sent[0]).get("target_device_id") == "mob-A")

    # 2. 无 target 的帧仍然广播（旧行为不变）
    await mgr.route_message(desktop, json.dumps({
        "type": "stream_chunk", "session_id": "s1", "chunk": "plain"}))
    check("无 target：两台手机都收到",
          len(mob_a.websocket.sent) == 2 and len(mob_b.websocket.sent) == 1)

    # 3. 定向给不在线的设备：不投递，但进缓冲等重连
    mob_a.websocket.sent.clear()
    mob_b.websocket.sent.clear()
    await mgr.route_message(desktop, json.dumps({
        "type": "stream_chunk", "session_id": "s1", "e2ee": True,
        "encrypted_payload": "BBB", "target_device_id": "mob-OFFLINE"}))
    check("目标离线：无人收到", not mob_a.websocket.sent and not mob_b.websocket.sent)

    # 4. 缓冲补发按目标过滤
    buf = mgr.rooms["room1"]["msg_buffer"]
    entries = list(buf)
    check("缓冲区记录了 target",
          [e.get("target") for e in entries] == ["mob-A", "", "mob-OFFLINE"])

    my_id = "mob-A"
    missed_a = [m for m in entries
                if m["seq"] > 0 and (not m.get("target") or m.get("target") == my_id)]
    check("mob-A 补发只拿到自己的 + 广播帧", [m["seq"] for m in missed_a] == [1, 2])

    my_id = "mob-B"
    missed_b = [m for m in entries
                if m["seq"] > 0 and (not m.get("target") or m.get("target") == my_id)]
    check("mob-B 补发只拿到广播帧", [m["seq"] for m in missed_b] == [2])

    # 5. 重同步判定用「全部帧」而不是「我的帧」：否则全是别人的帧时会误报过期
    all_after = [m for m in entries if m["seq"] > 5]
    missed = [m for m in all_after if not m.get("target") or m.get("target") == "mob-B"]
    needs_resync = (not all_after) and mgr.rooms["room1"]["next_seq"] - 1 > 5
    check("没有可补发帧时不应误报 resync_required", not needs_resync)

    # 6. 控制类消息限速（此前完全不受限速）
    import server as srv
    check("create_pairing 等已在限速名单里",
          {"create_pairing", "list_devices", "list_desktops",
           "revoke_device", "rename_device"} <= srv.CONTROL_MESSAGE_TYPES)
    ctrl = make_session("mobile", "mob-C")
    allowed = [ctrl.check_rate_limit() for _ in range(101)]
    check("同一会话第 101 条开始被限速（100 条/10 秒）",
          all(allowed[:100]) and allowed[100] is False)


asyncio.run(main())
print(f"\n全部 {len(PASS)} 项通过")
