package com.opencode.android.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * v5.0.3 (A-1): 控制类消息出站信封回归测试。
 *
 * 修复前全工程只有 sendPrompt 走 encryptInnerForDesktop，cancel /
 * tool_approval_response / create_session / file_list / file_read 仍发明文；
 * 而 agent 侧协商 E2EE 后对 CONTROL_ACTIONS 一律要求合法信封，明文帧只写
 * 一行 warning 就丢弃——手机端点「批准」无效、无法取消、文件浏览与新建会话不工作。
 *
 * 这里锁两件事：
 *  1. 客户端的 CONTROL_ACTIONS 与 desktop_agent/modules/e2ee.py 完全一致；
 *  2. 每一类动作在「已加密」时出站帧 e2ee=true 且**不含**明文 payload。
 */
class ControlEnvelopeTest {

    /** 与 desktop_agent/modules/e2ee.py::CONTROL_ACTIONS 逐项对应 */
    private val agentControlActions = setOf(
        "send_prompt", "cancel", "tool_approval_response",
        "create_session", "file_list", "file_read",
    )

    private fun payloadFor(action: String): JSONObject = when (action) {
        "send_prompt" -> JSONObject().put("prompt", "hi")
        "cancel" -> JSONObject()
        "tool_approval_response" -> JSONObject()
            .put("call_id", "call-1")
            .put("approved", true)
            .put("nonce", "n-1")
        "create_session" -> JSONObject().put("title", "t")
        "file_list", "file_read" -> JSONObject().put("path", "/tmp/a.txt")
        else -> JSONObject()
    }

    @Test
    fun `客户端 CONTROL_ACTIONS 与 agent 侧一致`() {
        assertEquals(agentControlActions, RelayWebSocketClient.CONTROL_ACTIONS.toSet())
    }

    @Test
    fun `逐个控制动作：加密后出站帧带 e2ee 且不含明文 payload`() {
        for (action in RelayWebSocketClient.CONTROL_ACTIONS) {
            val payload = payloadFor(action)
            val env = RelayMessageFactory.controlEnvelope(
                action = action,
                sessionId = "ses_1",
                payload = payload,
                clientMsgId = "cm-1",
                encryptedPayloadB64 = "BASE64CIPHERTEXT"
            )
            assertEquals(action, env.getString("action"))
            assertTrue("$action 必须置 e2ee=true", env.getBoolean("e2ee"))
            assertEquals("BASE64CIPHERTEXT", env.getString("encrypted_payload"))
            assertFalse("$action 加密后不得携带明文 payload", env.has("payload"))
            // 路由字段保持明文（agent 用它做 AAD 与投递）
            assertEquals("ses_1", env.getString("session_id"))
            assertEquals("cm-1", env.getString("client_msg_id"))
        }
    }

    @Test
    fun `未加密时退回明文 payload`() {
        val env = RelayMessageFactory.controlEnvelope(
            action = "cancel",
            sessionId = "ses_1",
            payload = JSONObject(),
            clientMsgId = "cm-1"
        )
        assertFalse(env.has("e2ee"))
        assertTrue(env.has("payload"))
        assertFalse(env.has("encrypted_payload"))
    }

    @Test
    fun `默认 req_id 取 client_msg_id`() {
        val env = RelayMessageFactory.controlEnvelope(
            action = "file_list", sessionId = "default",
            payload = JSONObject(), clientMsgId = "cm-9"
        )
        assertEquals("cm-9", env.getString("req_id"))
    }

    @Test
    fun `显式 req_id 覆盖默认值`() {
        val env = RelayMessageFactory.controlEnvelope(
            action = "send_prompt", sessionId = "ses_1",
            payload = JSONObject(), clientMsgId = "cm-1", reqId = "req-x"
        )
        assertEquals("req-x", env.getString("req_id"))
        assertEquals("cm-1", env.getString("client_msg_id"))
    }

    @Test
    fun `定向字段只在非空时出现`() {
        val withTarget = RelayMessageFactory.controlEnvelope(
            action = "send_prompt", sessionId = "ses_1",
            payload = JSONObject(), clientMsgId = "cm-1", targetDeviceId = "dev-9"
        )
        assertEquals("dev-9", withTarget.getString("target_device_id"))
        val without = RelayMessageFactory.controlEnvelope(
            action = "send_prompt", sessionId = "ses_1",
            payload = JSONObject(), clientMsgId = "cm-1", targetDeviceId = ""
        )
        assertFalse(without.has("target_device_id"))
    }

    @Test
    fun `内层只有 action 与 payload（seq 由 E2eeManager 补）`() {
        val inner = RelayMessageFactory.controlInner("cancel", JSONObject().put("k", "v"))
        assertEquals("cancel", inner.getString("action"))
        assertEquals("v", inner.getJSONObject("payload").getString("k"))
        assertFalse("内层不得预先写死 seq", inner.has("seq"))
    }
}