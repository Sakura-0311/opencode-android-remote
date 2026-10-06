"""v5.0.1 修复回归测试：python3 desktop_agent/modules/test_hardening.py

覆盖三处已确认缺陷：
  1. 审批事件契约：上游真实事件是 message.part.updated / permission.updated，
     且 Permission 是扁平结构（title/metadata），旧的 permission.asked +
     tool.name/diff/file_path 假设会让手机端根本不弹审批框、或弹出空框盲签。
  2. E2EE 多对端静默降级明文：协商了对端但无法确定唯一对端时，
     encrypt_outgoing(strict=True) 必须抛 E2EEUnavailable，绝不返回明文。
  3. 内容型消息统一出口 send_d2m_secure：加密不可用时阻止外发并回报错误。
"""
import asyncio
import json
import os
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.dirname(_HERE))  # desktop_agent/，供 import modules.*

_tmp = tempfile.mkdtemp(prefix="hardening_test_")
os.environ["E2EE_CONFIG_DIR"] = _tmp
os.environ["E2EE_ENABLED"] = "1"

import e2ee                      # noqa: E402
import state                     # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


# ==============================================================================
# 1. 事件名契约
# ==============================================================================
check("增量事件包含上游真实名 message.part.updated",
      "message.part.updated" in state.DELTA_EVENT_TYPES)
check("审批事件包含上游真实名 permission.updated",
      "permission.updated" in state.PERMISSION_EVENT_TYPES)
check("旧事件名仍兼容",
      {"message.part.delta", "permission.asked"} <= set(state.DELTA_EVENT_TYPES)
      | set(state.PERMISSION_EVENT_TYPES))

# message.part.updated 只取 delta，不把 part.text 当增量（否则重复刷屏）
part_evt = {"type": "message.part.updated",
            "properties": {"part": {"type": "text", "text": "FULL SO FAR"},
                           "delta": "tok"}}
check("part.updated 取 delta", state._extract_event_delta(part_evt) == "tok")
check("part.updated 无 delta 时返回空（不拿 part.text 重发全量）",
      state._extract_event_delta(
          {"type": "message.part.updated",
           "properties": {"part": {"type": "text", "text": "FULL"}}}) == "")

# ==============================================================================
# 2. 审批事件解析：官方 permission.updated 真实结构
# ==============================================================================
bash_evt = {
    "type": "permission.updated",
    "properties": {
        "id": "per_abc123",
        "type": "bash",
        "sessionID": "ses_1",
        "messageID": "msg_1",
        "callID": "call_1",
        "title": "rm -rf ./build",
        "metadata": {"command": "rm -rf ./build", "patterns": ["rm *"]},
        "time": {"created": 1},
    },
}
p = state.parse_permission_event(bash_evt)
check("官方结构识别 permission id", p["permission_id"] == "per_abc123")
check("官方 type 映射 tool_name", p["tool_name"] == "bash")
check("官方 title 成为 summary", p["summary"] == "rm -rf ./build")
check("metadata.command 进入详情区（不再空框）",
      p["command"] == "rm -rf ./build" and "rm -rf ./build" in p["raw_content"])
check("patterns 透传", p["patterns"] == ["rm *"])
check("详情有可渲染行", any(l["content"] == "rm -rf ./build" for l in p["diff_lines"]))

edit_evt = {
    "type": "permission.updated",
    "properties": {
        "id": "per_edit1", "type": "edit", "title": "修改 src/app.py",
        "pattern": "src/app.py",
        "metadata": {"diff": "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new"},
    },
}
e = state.parse_permission_event(edit_evt)
check("统一 diff 行分类正确（文件头归 HEADER，不计入增删）",
      [l["type"] for l in e["diff_lines"]] ==
      ["HEADER", "HEADER", "HEADER", "REMOVED", "ADDED"])
check("edit 的 pattern 透传", e["patterns"] == "src/app.py")
check("有 diff 时不被 command 覆盖", e["raw_content"].startswith("--- a"))

legacy_evt = {"type": "permission.asked",
              "properties": {"permission_id": "per_old", "tool": {"name": "write", "path": "/x"},
                             "summary": "旧结构"}}
l = state.parse_permission_event(legacy_evt)
check("旧嵌套结构仍可解析",
      l["permission_id"] == "per_old" and l["tool_name"] == "write"
      and l["file_path"] == "/x" and l["summary"] == "旧结构")

fallback = state.parse_permission_event({"type": "permission.updated", "properties": {}})
check("字段全缺时生成 id 且 summary 不为空",
      bool(fallback["permission_id"]) and bool(fallback["summary"]))

# ==============================================================================
# 3. E2EE 多对端：strict 必须 fail-closed
# ==============================================================================
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey  # noqa: E402
import base64 as _b64  # noqa: E402


def b64(b):
    return _b64.b64encode(b).decode()


# 保留每台「手机」的私钥（raw bytes，derive_shared_key 要求 32 字节裸钥），
# 供后面验证「每份密文只有对应手机能解」
PEER_KEYS = {}
for peer in ("mob-1", "mob-2"):
    _pk = X25519PrivateKey.generate()
    PEER_KEYS[peer] = (_pk.private_bytes_raw(), _pk.public_key().public_bytes_raw())
    e2ee.save_peer_pubkey(peer, b64(PEER_KEYS[peer][1]))
check("已协商 2 个对端", len(e2ee.list_peer_ids()) == 2)

# 本机（desktop）密钥与 AAD 用的 sender id。
# 注意 AAD 里的 sender 必须是 send_d2m_secure 隐式使用的那一个
# （encrypt_outgoing 不传 sender_device_id 时取 config.get_desktop_device_id()），
# 写死字符串会导致解密端 AAD 不匹配而 InvalidTag。
from modules import config as _cfg  # noqa: E402

_desk_priv_raw, _desk_pub_b64 = e2ee.get_or_create_keypair()
DESK_ID = _cfg.get_desktop_device_id()
DESK_RAW_PUB = _b64.b64decode(_desk_pub_b64)

chunk = {"type": "stream_chunk", "session_id": "s1", "chunk": "SECRET-CODE"}

try:
    e2ee.encrypt_outgoing(chunk, strict=True)
    check("未指定对端且 strict 时必须抛 E2EEUnavailable", False)
except e2ee.E2EEUnavailable:
    check("未指定对端 strict 抛 E2EEUnavailable（不返回明文）", True)

# 非 strict 路径保留旧行为（供明确知道自己在做什么的调用方），但必须是明文
loose = e2ee.encrypt_outgoing(chunk)
check("非 strict 路径仍透传（向后兼容）", loose.get("e2ee") is not True)

# 明确指定对端时照常加密
sealed = e2ee.encrypt_outgoing(chunk, peer_device_id="mob-1", sender_device_id="desk-1")
check("指定对端可正常加密", sealed.get("e2ee") is True and "chunk" not in sealed)

# 非内容型消息不受 strict 影响（error/pong 本就该明文）
err = {"type": "error", "session_id": "s1", "message": "x"}
check("非内容型消息 strict 下原样通过",
      e2ee.encrypt_outgoing(err, strict=True).get("e2ee") is not True)


# ==============================================================================
# 4. send_d2m_secure：加密不可用时阻止外发
# ==============================================================================
class FakeWS:
    def __init__(self):
        self.sent = []

    async def send(self, data):
        self.sent.append(json.loads(data))


async def main_async():
    # --- v5.0.2：多对端不再 fail-closed，而是「每个对端各加密一份 + 定向投递」---
    ws = FakeWS()
    ok = await state.send_d2m_secure(ws, dict(chunk))
    check("多对端时发送成功（不再是拒发）", ok is True)
    check("为每个对端各发一份", len(ws.sent) == 2)
    check("两份都加密", all(m.get("e2ee") is True for m in ws.sent))
    check("两份都不含明文内容", all("chunk" not in m for m in ws.sent))
    targets = sorted(m.get("target_device_id") for m in ws.sent)
    check("两份分别定向到两台手机", targets == ["mob-1", "mob-2"])
    check("两份编号不同（每个对端独立 seq）",
          ws.sent[0]["encrypted_payload"] != ws.sent[1]["encrypted_payload"])

    # 每份都能被对应手机用自己的 k_d2m 解开，且换错对端解不开
    for m in ws.sent:
        peer = m["target_device_id"]
        priv, _ = PEER_KEYS[peer]
        shared = e2ee.derive_shared_key(priv, DESK_RAW_PUB)
        _, k_d2m = e2ee.derive_msg_keys(shared)
        inner = json.loads(e2ee.decrypt(m["encrypted_payload"], k_d2m,
                                        DESK_ID, "s1").decode())
        check(f"{peer} 能解开自己的那份且内容正确",
              inner.get("chunk") == "SECRET-CODE")
        other = "mob-2" if peer == "mob-1" else "mob-1"
        opriv, _ = PEER_KEYS[other]
        oshared = e2ee.derive_shared_key(opriv, DESK_RAW_PUB)
        _, ok_d2m = e2ee.derive_msg_keys(oshared)
        try:
            e2ee.decrypt(m["encrypted_payload"], ok_d2m, DESK_ID, "s1")
            check(f"{other} 不应能解开发给 {peer} 的那份", False)
        except Exception:
            check(f"{other} 解不开发给 {peer} 的那份", True)

    # 单对端：仍然只发一份，不额外指定 target（保持旧行为最简单形态）
    one = tempfile.mkdtemp(prefix="hardening_one_peer_")
    os.environ["E2EE_CONFIG_DIR"] = one
    e2ee.save_peer_pubkey("only-mobile",
                          b64(X25519PrivateKey.generate().public_key().public_bytes_raw()))
    check("单对端场景就绪", len(e2ee.list_peer_ids()) == 1)
    ws2 = FakeWS()
    ok2 = await state.send_d2m_secure(ws2, dict(chunk))
    check("单对端时正常加密外发",
          ok2 is True and len(ws2.sent) == 1 and ws2.sent[0].get("e2ee") is True
          and "chunk" not in ws2.sent[0])


asyncio.run(main_async())


# ==============================================================================
# 5. E2EE 开启但加密库缺失：必须拒绝启动，不得静默走明文
# ==============================================================================
import subprocess  # noqa: E402

_fake_pkg = tempfile.mkdtemp(prefix="hardening_no_crypto_")
with open(os.path.join(_fake_pkg, "cryptography.py"), "w", encoding="utf-8") as f:
    f.write("raise ImportError('simulated: cryptography not installed')\n")

_env = dict(os.environ)
_env["E2EE_ENABLED"] = "1"
_env["PYTHONIOENCODING"] = "utf-8"
_env["PYTHONPATH"] = os.pathsep.join([_fake_pkg, os.path.dirname(_HERE)])


def _run_py(code, env):
    """跨平台跑子进程：显式 utf-8 解码（Windows 默认 GBK 会炸中文输出）。"""
    return subprocess.run([sys.executable, "-c", code],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding="utf-8", errors="replace",
                          env=env, cwd=os.path.dirname(_HERE))


_r = _run_py("from modules import state; state._e2ee()", _env)
_r_out = _r.stdout or ""
check("E2EE 开启但缺 cryptography 时拒绝启动（非零退出）", _r.returncode != 0)
check("报错文案指明如何修复",
      "cryptography" in _r_out and "E2EE_ENABLED=0" in _r_out)

# 关闭 E2EE 时不应触发（旧明文流程不受影响）
_env0 = dict(_env, E2EE_ENABLED="0")
_r0 = _run_py("from modules import state; assert state._e2ee() is None; print('ok')", _env0)
check("E2EE 关闭时正常（明文流程不受影响）",
      _r0.returncode == 0 and "ok" in (_r0.stdout or ""))

print(f"\n全部 {len(PASS)} 项通过")
