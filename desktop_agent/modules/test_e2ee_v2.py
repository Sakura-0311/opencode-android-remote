"""E2EE v4.6.0 互操作测试：python3 desktop_agent/modules/test_e2ee_v2.py

1. 读取 tests/e2ee/interop_vectors.json（与 Kotlin 单测共用同一份）：
   - HKDF 派生 k_m2d/k_d2m 与向量一致
   - 固定 nonce 向量可解密，内层 JSON 与 AAD 绑定正确
   - AAD 篡改 -> 解密失败
2. 真实代码整链（非 mock）：
   - m2d: 按手机侧约定构造内层 {"action","payload","seq"} -> encrypt
     -> decrypt_incoming -> action/payload 还原、_e2ee_ok 置位
   - 序号重放 -> _e2ee_failed，control_allowed 拒绝
   - 明文控制消息在已协商后 -> control_allowed 拒绝（fail-closed）
   - d2m: encrypt_outgoing(stream_chunk/tool_approval_request) -> 内层 JSON 可解密
"""
import base64
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

_tmp = tempfile.mkdtemp(prefix="e2ee_v2_test_")
os.environ["E2EE_CONFIG_DIR"] = _tmp
os.environ["E2EE_ENABLED"] = "1"

from cryptography.exceptions import InvalidTag

import e2ee

PASS = []


def check(name, cond):
    PASS.append(bool(cond))
    print(f"[{'PASS' if cond else 'FAIL'}] {name}")
    if not cond:
        raise AssertionError(name)


VEC_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "..", "tests", "e2ee", "interop_vectors.json")
VEC_PATH = os.path.normpath(VEC_PATH)
vec = json.load(open(VEC_PATH, encoding="utf-8"))

# ---- 1. 向量一致性 ----
mob_priv = base64.b64decode(vec["mobile_priv_b64"])
desk_pub = base64.b64decode(vec["desktop_pub_b64"])
desk_priv = base64.b64decode(vec["desktop_priv_b64"])
mob_pub = base64.b64decode(vec["mobile_pub_b64"])
k_m2d, k_d2m = e2ee.derive_msg_keys(e2ee.derive_shared_key(mob_priv, desk_pub))
check("HKDF k_m2d 与向量一致", k_m2d.hex() == vec["k_m2d_hex"])
check("HKDF k_d2m 与向量一致", k_d2m.hex() == vec["k_d2m_hex"])

for v in vec["vectors"]:
    key = k_m2d if v["direction"] == "m2d" else k_d2m
    pt = e2ee.decrypt(v["encrypted_payload_b64"], key,
                      v["sender_device_id"], v["session_id"])
    check(f"{v['direction']} 向量解密成功", json.loads(pt.decode()) == v["inner_json"])
    try:
        e2ee.decrypt(v["encrypted_payload_b64"], key, "wrong-sender", v["session_id"])
        check(f"{v['direction']} AAD 篡改应失败", False)
    except InvalidTag:
        check(f"{v['direction']} AAD 篡改被拒绝", True)

# ---- 2. 真实代码整链 ----
# 先写入本机（desktop）私钥：用向量里的 desktop 私钥，使 derive 与向量一致
with open(os.path.join(_tmp, "e2ee_privkey"), "w") as f:
    f.write(vec["desktop_priv_b64"])
# 注册对端：desktop 侧保存手机公钥（peer id = 手机 relay device_id）
MOBILE_ID = "mob-relay-id-1"
e2ee.save_peer_pubkey(MOBILE_ID, vec["mobile_pub_b64"])
check("对端公钥已注册", MOBILE_ID in e2ee.list_peer_ids())

# 手机侧约定：内层 {"action","payload","seq"}，AAD sender=手机 relay device_id
inner = {"action": "send_prompt",
         "payload": {"prompt": "hello", "model": {"providerID": "p", "modelID": "m"}},
         "seq": 1}
ct = e2ee.encrypt(json.dumps(inner, ensure_ascii=False).encode("utf-8"),
                  k_m2d, MOBILE_ID, "sess-1")
msg = {"action": "send_prompt", "session_id": "sess-1", "payload": {},
       "e2ee": True, "encrypted_payload": ct}
out = e2ee.decrypt_incoming(msg)
check("m2d 解密成功置 _e2ee_ok", out.get("_e2ee_ok") is True)
check("m2d payload 还原（含 model）",
      out["payload"] == inner["payload"] and out["action"] == "send_prompt")
check("协商后合法信封放行", e2ee.control_allowed("send_prompt", out))

# 重放同一序号 -> 拒绝
msg2 = {"action": "send_prompt", "session_id": "sess-1", "payload": {},
        "e2ee": True, "encrypted_payload": ct}
out2 = e2ee.decrypt_incoming(msg2)
check("序号重放被标记失败", out2.get("_e2ee_failed") == "replay/old seq=1")
check("重放消息 control_allowed 拒绝", not e2ee.control_allowed("send_prompt", out2))

# 明文控制消息在已协商后 -> fail-closed 拒绝
plain = {"action": "send_prompt", "session_id": "sess-1",
         "payload": {"prompt": "evil"}, "client_msg_id": "x"}
check("明文 send_prompt 被拒绝", not e2ee.control_allowed("send_prompt", plain))
check("明文 cancel 被拒绝", not e2ee.control_allowed("cancel", plain))
check("明文 ping 不受影响", e2ee.control_allowed("ping", plain))
# 未协商（换空配置目录）时明文放行——旧流程不受影响
import tempfile as _tf
_empty = _tf.mkdtemp(prefix="e2ee_empty_")
_old_dir = os.environ["E2EE_CONFIG_DIR"]
os.environ["E2EE_CONFIG_DIR"] = _empty
check("未协商时明文 send_prompt 放行", e2ee.control_allowed("send_prompt", plain))
os.environ["E2EE_CONFIG_DIR"] = _old_dir

# d2m: encrypt_outgoing 加密 stream_chunk 与审批请求
sc = {"type": "stream_chunk", "session_id": "sess-1", "chunk": "hi"}
enc = e2ee.encrypt_outgoing(sc, sender_device_id="desk-relay-id-1")
check("stream_chunk 被加密", enc.get("e2ee") is True and "chunk" not in enc)
inner_pt = e2ee.decrypt(enc["encrypted_payload"], k_d2m, "desk-relay-id-1", "sess-1")
inner_o = json.loads(inner_pt.decode())
check("d2m 内层为 JSON 且含 chunk+seq",
      inner_o.get("chunk") == "hi" and inner_o.get("seq") == 1)

appr = {"type": "tool_approval_request", "session_id": "sess-1",
        "call_id": "c1", "tool_name": "edit", "diff_lines": [],
        "raw_content": "secret diff", "nonce": "n", "expires_at": 1}
enc2 = e2ee.encrypt_outgoing(appr, sender_device_id="desk-relay-id-1")
check("审批请求被加密", enc2.get("e2ee") is True and "raw_content" not in enc2)

print(f"\n全部 {len(PASS)} 项通过")
