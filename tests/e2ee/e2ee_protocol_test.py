#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2EE 线格式一致性 + 真实代码整链测试（v5.0.1 重写）。

历史问题：本文件曾自己复制一份加密实现（info 用 `opencode-e2ee-d2m`、
salt=sha256(pub_d||pub_m)、AAD 用 `|`），却从不 import 真实模块，
于是「真实 E2EE 全坏也会 E2EE_PASS」——是自欺型测试。
现在改为：

  1. **文档 ↔ Python ↔ Kotlin 三方一致性**（纯文本比对，无需编译 Android）：
     HKDF info 字符串、salt、AAD 拼接格式必须完全一致。
     这条能拦住「Python 改了 AAD 分隔符但 Kotlin 没改」这类真实故障。
  2. **真实代码整链**：只调用 desktop_agent 的真实模块（e2ee.py），
     手机侧严格按 docs/E2EE_WIRE_v1.md 用真实函数派生/加密，
     再喂给真实 decrypt_incoming / control_allowed 校验。

无需 relay、无需 websocket-client、无需 Android SDK。
"""
import base64
import json
import os
import re
import sys
import tempfile

_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_ROOT, "desktop_agent", "modules"))

_tmp = tempfile.mkdtemp(prefix="e2ee_proto_test_")
os.environ["E2EE_CONFIG_DIR"] = _tmp
os.environ["E2EE_ENABLED"] = "1"

import e2ee  # noqa: E402

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


def read(rel):
    with open(os.path.join(_ROOT, rel), encoding="utf-8") as f:
        return f.read()


# ==============================================================================
# 1. 文档 ↔ Python ↔ Kotlin 一致性
# ==============================================================================
doc = read("docs/E2EE_WIRE_v1.md")
kt = read("android_app/app/src/main/java/com/opencode/android/security/E2eeCrypto.kt")

check("Python info 与文档一致",
      e2ee._INFO_M2D.decode() in doc and e2ee._INFO_D2M.decode() in doc)
kt_info_m2d = re.search(r'INFO_M2D\s*=\s*"([^"]+)"', kt)
kt_info_d2m = re.search(r'INFO_D2M\s*=\s*"([^"]+)"', kt)
check("Kotlin 侧 info 常量存在", bool(kt_info_m2d and kt_info_d2m))
check("Python 与 Kotlin 的 info 完全一致",
      kt_info_m2d.group(1) == e2ee._INFO_M2D.decode()
      and kt_info_d2m.group(1) == e2ee._INFO_D2M.decode())

check("Python AAD 为 sender:session",
      e2ee._aad("dev1", "sess1") == b"dev1:sess1")
check("Kotlin AAD 拼接与 Python 相同",
      '"$senderDeviceId:$sessionId"' in kt)
# 文档里 AAD 模板必须也是冒号
check("文档 AAD 模板与实现一致",
      '"{sender_device_id}:{session_id}"' in doc)

check("两端 salt 均为 32 零字节",
      "bytes(32)" in doc and "ByteArray(32)" in kt)

# ==============================================================================
# 2. 真实代码整链：换密钥协商 -> 加密 -> 解密 -> 防重放 -> 控制消息放行
# ==============================================================================
desk_priv, desk_pub_b64 = e2ee.get_or_create_keypair()
mob_priv, mob_pub_raw = e2ee.generate_keypair()
MOBILE_ID = "mock-mobile-1"
e2ee.save_peer_pubkey(MOBILE_ID, base64.b64encode(mob_pub_raw).decode("ascii"))

shared_m = e2ee.derive_shared_key(mob_priv, base64.b64decode(desk_pub_b64))
shared_d = e2ee.derive_shared_key(desk_priv, mob_pub_raw)
check("两端 ECDH 共享密钥一致", shared_m == shared_d)

k_m2d_m, k_d2m_m = e2ee.derive_msg_keys(shared_m)
k_m2d_d, k_d2m_d = e2ee.derive_msg_keys_for_peer(MOBILE_ID)
check("两端方向隔离密钥一致",
      (k_m2d_m, k_d2m_m) == (k_m2d_d, k_d2m_d))
check("m2d 与 d2m 方向隔离（不同密钥）", k_m2d_m != k_d2m_m)

# --- m2d：手机侧按线格式构造 {"action","payload","seq"} ---
session_id = "sess-proto-1"
inner = {"action": "send_prompt",
         "payload": {"prompt": "真实整链：你好", "model": {"providerID": "p", "modelID": "m"}},
         "seq": 1}
ct = e2ee.encrypt(json.dumps(inner, ensure_ascii=False).encode("utf-8"),
                  k_m2d_m, MOBILE_ID, session_id)
msg = {"action": "send_prompt", "session_id": session_id, "payload": {},
       "e2ee": True, "encrypted_payload": ct}
out = e2ee.decrypt_incoming(msg)
check("真实 decrypt_incoming 解密并置 _e2ee_ok", out.get("_e2ee_ok") is True)
check("payload/action 完整还原",
      out["action"] == "send_prompt" and out["payload"] == inner["payload"])
check("协商后合法信封放行控制消息", e2ee.control_allowed("send_prompt", out))

# AAD 篡改（换 session）必须解不开
bad = dict(msg, session_id="other-session",
           encrypted_payload=e2ee.encrypt(json.dumps(inner).encode(), k_m2d_m,
                                          MOBILE_ID, "other-session"))
check("AAD 绑定生效（跨会话重放被拒）",
      e2ee.decrypt_incoming(bad).get("_e2ee_failed") is not None)

# 序号重放必须被拒（注意：必须用全新的 dict，真实场景每条消息都是新解析的；
# 复用已解密过的 dict 会把上一次的 _e2ee_ok 带过来）
replay = e2ee.decrypt_incoming({"action": "send_prompt", "session_id": session_id,
                                "payload": {}, "e2ee": True,
                                "encrypted_payload": ct})
check("序号重放被拒", replay.get("_e2ee_failed") == "replay/old seq=1")
check("重放消息 control_allowed 拒绝", not e2ee.control_allowed("send_prompt", replay))

# 明文控制消息在已协商后 fail-closed
plain = {"action": "send_prompt", "session_id": session_id, "payload": {"prompt": "evil"}}
check("明文 control 消息被拒（fail-closed）",
      not e2ee.control_allowed("send_prompt", plain))
check("明文 ping 不受影响", e2ee.control_allowed("ping", plain))

# --- d2m：真实 encrypt_outgoing -> 手机侧真实 decrypt ---
enc = e2ee.encrypt_outgoing({"type": "stream_chunk", "session_id": session_id, "chunk": "秘密"},
                            peer_device_id=MOBILE_ID, sender_device_id="desk-relay-1")
check("d2m 内容被加密（明文字段不外泄）",
      enc.get("e2ee") is True and "chunk" not in enc)
inner_o = json.loads(e2ee.decrypt(enc["encrypted_payload"], k_d2m_m,
                                  "desk-relay-1", session_id).decode("utf-8"))
check("手机侧可解开且内容/序号正确",
      inner_o.get("chunk") == "秘密" and inner_o.get("seq") == 1)

# --- 多对端：strict 必须 fail-closed（回归 v5.0.1）---
e2ee.save_peer_pubkey("mock-mobile-2", base64.b64encode(mob_pub_raw).decode("ascii"))
try:
    e2ee.encrypt_outgoing({"type": "stream_chunk", "session_id": session_id, "chunk": "x"},
                          strict=True)
    check("多对端 strict 抛 E2EEUnavailable", False)
except e2ee.E2EEUnavailable:
    check("多对端 strict 抛 E2EEUnavailable（不降级明文）", True)

print(f"\n全部 {len(PASS)} 项通过")
