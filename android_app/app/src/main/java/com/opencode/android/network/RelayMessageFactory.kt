package com.opencode.android.network

import org.json.JSONObject
import java.util.UUID

/**
 * B2: Relay 消息信封构造工厂（从 RelayWebSocketClient 抽出）。
 *
 * 原 client 里 14 个 sendXxx 方法都是"拼 JSONObject + webSocket.send"，
 * 拼装部分是纯函数，抽到这里后可单测协议字段；发送仍留在 client。
 * reqId/clientMsgId 默认随机生成，测试时可显式传入保证确定性。
 */
object RelayMessageFactory {

    fun listSessions(reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "list_sessions")
            put("req_id", reqId)
        }

    fun listDevices(): JSONObject =
        JSONObject().apply { put("type", "list_devices") }

    fun listDesktops(): JSONObject =
        JSONObject().apply { put("type", "list_desktops") }

    fun revokeDevice(deviceId: String, deviceName: String = ""): JSONObject =
        JSONObject().apply {
            put("type", "revoke_device")
            put("device_id", deviceId)
            put("device_name", deviceName)
        }

    fun renameDeviceById(deviceId: String, oldName: String, newName: String): JSONObject =
        JSONObject().apply {
            put("type", "rename_device")
            put("device_id", deviceId)
            put("old_name", oldName)
            put("new_name", newName)
        }

    fun renameDevice(oldName: String, newName: String): JSONObject =
        JSONObject().apply {
            put("type", "rename_device")
            put("old_name", oldName)
            put("new_name", newName)
        }

    fun diagnose(reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "diagnose")
            put("req_id", reqId)
        }

    fun getConfig(reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "get_config")
            put("req_id", reqId)
        }

    fun getProjects(reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "get_projects")
            put("req_id", reqId)
        }

    /**
     * v5.0.3 (A-1): 控制类消息的**内层**明文（加密对象）。
     * 形状固定为 {"action","payload"}，`seq` 由 E2eeManager.encryptInnerForDesktop 补写。
     */
    fun controlInner(action: String, payload: JSONObject): JSONObject =
        JSONObject().apply {
            put("action", action)
            put("payload", payload)
        }

    /**
     * v5.0.3 (A-1): 控制类消息的**外层**信封（纯函数，可 JVM 单测）。
     *
     * 外层只放明文路由字段（action/session_id/req_id/client_msg_id/target_device_id）；
     * 给了 [encryptedPayloadB64] 就走 E2EE 信封且**不带**明文 payload，
     * 否则放明文 payload。形状见 docs/E2EE_WIRE_v1.md。
     */
    fun controlEnvelope(
        action: String,
        sessionId: String,
        payload: JSONObject,
        clientMsgId: String,
        reqId: String = clientMsgId,
        targetDeviceId: String? = null,
        encryptedPayloadB64: String? = null
    ): JSONObject = JSONObject().apply {
        put("action", action)
        put("session_id", sessionId)
        put("req_id", reqId)
        put("client_msg_id", clientMsgId)
        if (!targetDeviceId.isNullOrEmpty()) put("target_device_id", targetDeviceId)
        if (encryptedPayloadB64 != null) {
            put("e2ee", true)
            put("encrypted_payload", encryptedPayloadB64)
        } else {
            put("payload", payload)
        }
    }
}
