"""v5.0.3 (B-3) 回归测试：审批被 agent 拒绝时必须回报手机端。

跑法：python3 desktop_agent/modules/test_approval_reject.py

此前 protocol.handle_mobile_message 对「缺 nonce / nonce 失效」只写一行
warning 就 return：手机端点了批准、界面显示已处理，agent 实际丢弃，
任务永远卡在等审批，用户得不到任何提示。现在这两种拒绝都会回一帧
approval_rejected（附加帧，旧 App 的 else -> {} 会忽略）。
"""
import asyncio
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.dirname(_HERE))  # opencode_api 等平铺模块在 desktop_agent/ 下

import protocol  # noqa: E402
from modules.state import tool_guard  # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


class FakeWS:
    def __init__(self):
        self.sent = []

    async def send(self, text):
        self.sent.append(json.loads(text))


def call_with(payload):
    ws = FakeWS()
    msg = {
        "action": "tool_approval_response",
        "req_id": "req-1",
        "session_id": "ses_1",
        "payload": payload,
    }
    asyncio.run(protocol.handle_mobile_message(msg, ws, None))
    return ws.sent


# ---- 1. 缺 nonce ----
frames = call_with({"call_id": "call-1", "approved": True})
check("缺 nonce 时回一帧", len(frames) == 1)
check("帧类型是 approval_rejected", frames[0]["type"] == "approval_rejected")
check("带回 call_id", frames[0]["call_id"] == "call-1")
check("原因是 missing_nonce", frames[0]["reason"] == "missing_nonce")

# ---- 2. nonce 失效（未注册过的 call_id） ----
frames = call_with({"call_id": "never-issued", "approved": True, "nonce": "deadbeef"})
check("nonce 失效时回一帧", len(frames) == 1)
check("帧类型是 approval_rejected", frames[0]["type"] == "approval_rejected")
check("原因是 nonce_invalid_or_expired",
      frames[0]["reason"] == "nonce_invalid_or_expired")

# ---- 3. 缺 call_id ----
frames = call_with({"approved": True, "nonce": "deadbeef"})
check("缺 call_id 时回一帧", len(frames) == 1)
check("原因是 missing_call_id", frames[0]["reason"] == "missing_call_id")
check("call_id 字段为空串", frames[0]["call_id"] == "")

# ---- 4. 合法 nonce 走正常路径，不发拒绝帧（不回归） ----
_calls = []


async def _fake_respond(http_session, session_id, permission_id, approved, reason, url, password):
    _calls.append({"session_id": session_id, "permission_id": permission_id,
                   "approved": approved})
    return True


record = tool_guard.create_approval("ses_1", "call-ok", "edit_file", {})
record = tool_guard.create_approval("ses_1", "call-ok", "edit_file", {})
_real_respond = protocol.respond_to_permission
protocol.respond_to_permission = _fake_respond
try:
    frames = call_with({"call_id": "call-ok", "approved": True,
                        "nonce": record["nonce"]})
finally:
    protocol.respond_to_permission = _real_respond
check("合法 nonce 不回 approval_rejected",
      all(f["type"] != "approval_rejected" for f in frames))
check("合法 nonce 正常转发到 opencode 权限端点", _calls[0]["permission_id"] == "call-ok")
check("合法 nonce 送达后删除审批记录",
      tool_guard.validate_approval("call-ok", record["nonce"]) is None)

print(f"\n全部 {len(PASS)} 项通过")