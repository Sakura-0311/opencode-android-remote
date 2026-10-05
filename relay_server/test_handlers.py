#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.8.0/E3: relay 消息路由 handler 测试（route_message）。
覆盖：未认证丢弃、限速丢弃、mobile→主 desktop、定向 target_device_id、
目标不在线报错。"""
import asyncio
import json
import os
import sys

os.environ.setdefault("RELAY_ADMIN_TOKEN", "test-admin-token-0123456789abcdef")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server
from server import ConnectionManager, ClientSession


class FakeWebSocket:
    def __init__(self):
        self.sent = []

    async def send_text(self, msg: str):
        self.sent.append(msg)


def make_session(client_type, account_id="room1", authenticated=True, device_id=""):
    ws = FakeWebSocket()
    s = ClientSession(ws, client_type, account_id, "127.0.0.1")
    s.is_authenticated = authenticated
    s.device_id = device_id
    return s, ws


def make_room(mgr, account_id="room1", desktop_device="d1"):
    d_sess, d_ws = make_session("desktop", account_id, device_id=desktop_device)
    m_sess, m_ws = make_session("mobile", account_id, device_id="m1")
    mgr.rooms[account_id] = {
        "secret_hash": "x",
        "desktop": d_sess,
        "desktops": {desktop_device: d_sess},
        "mobiles": {m_sess},
        "msg_buffer": server.RoomBuffer(),
        "next_seq": 1,
        "device_secrets": {},
    }
    return (d_sess, d_ws), (m_sess, m_ws)


passed = failed = 0

def check(name, cond):
    global passed, failed
    if cond:
        passed += 1
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name}")


async def main():
    # 1. 未认证发送者：直接丢弃
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    m_sess.is_authenticated = False
    await mgr.route_message(m_sess, '{"type":"send_prompt"}')
    check("未认证消息被丢弃", d_ws.sent == [] and m_ws.sent == [])

    # 2. 限速超限：丢弃
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    for _ in range(105):
        m_sess.check_rate_limit()
    await mgr.route_message(m_sess, '{"type":"send_prompt"}')
    check("限速超限消息被丢弃", d_ws.sent == [])

    # 3. mobile→主 desktop（无 target_device_id 快路径）
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    await mgr.route_message(m_sess, '{"type":"send_prompt","payload":{}}')
    check("mobile 路由到主 desktop", len(d_ws.sent) == 1 and "send_prompt" in d_ws.sent[0])

    # 4. 定向 target_device_id
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    d2_sess, d2_ws = make_session("desktop", "room1", device_id="d2")
    mgr.rooms["room1"]["desktops"]["d2"] = d2_sess
    await mgr.route_message(m_sess, '{"type":"send_prompt","target_device_id":"d2"}')
    check("定向路由到 d2", len(d2_ws.sent) == 1 and d_ws.sent == [])

    # 5. 目标不在线：mobile 收到 DESKTOP_OFFLINE
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    await mgr.route_message(m_sess, '{"type":"send_prompt","target_device_id":"ghost"}')
    err = json.loads(m_ws.sent[0]) if m_ws.sent else {}
    check("目标不在线报错", err.get("code") == "DESKTOP_OFFLINE"
          and err.get("target_device_id") == "ghost")

    # 6. desktop→mobile 扇出
    mgr = ConnectionManager()
    (d_sess, d_ws), (m_sess, m_ws) = make_room(mgr)
    await mgr.route_message(d_sess, '{"type":"stream_chunk","chunk":"hi"}')
    check("desktop 扇出到 mobile", len(m_ws.sent) == 1 and "stream_chunk" in m_ws.sent[0])


asyncio.run(main())
print(f"\n共 {passed + failed} 项，{'全部通过' if failed == 0 else f'{failed} 项失败'}")
sys.exit(1 if failed else 0)
