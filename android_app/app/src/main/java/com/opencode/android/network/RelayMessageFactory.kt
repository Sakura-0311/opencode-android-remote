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

    fun createSession(title: String, reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "create_session")
            put("req_id", reqId)
            put("payload", JSONObject().apply { put("title", title) })
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

    fun fileList(path: String, reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "file_list")
            put("req_id", reqId)
            put("payload", JSONObject().apply { put("path", path) })
        }

    fun fileRead(path: String, reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "file_read")
            put("req_id", reqId)
            put("payload", JSONObject().apply { put("path", path) })
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

    fun approvalResponse(callId: String, isApproved: Boolean, reason: String = "",
                         nonce: String? = null,
                         reqId: String = UUID.randomUUID().toString()): JSONObject {
        val payload = JSONObject().apply {
            put("call_id", callId)
            put("approved", isApproved)
            put("reason", reason)
            // B-5: nonce 原样回传，供 agent 防重放校验
            if (!nonce.isNullOrEmpty()) put("nonce", nonce)
        }
        return JSONObject().apply {
            put("action", "tool_approval_response")
            put("req_id", reqId)
            put("payload", payload)
        }
    }

    fun cancel(sessionId: String, clientMsgId: String = UUID.randomUUID().toString(),
               reqId: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().apply {
            put("action", "cancel")
            put("session_id", sessionId)
            put("req_id", reqId)
            put("client_msg_id", clientMsgId)
        }
}
