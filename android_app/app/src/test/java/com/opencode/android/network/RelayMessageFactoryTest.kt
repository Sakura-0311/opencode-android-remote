package com.opencode.android.network

import org.junit.Assert.*
import org.junit.Test

/**
 * B2: RelayMessageFactory 单测（先补测试再拆）。
 * 断言协议字段结构；reqId 显式传入保证确定性。
 */
class RelayMessageFactoryTest {

    @Test fun listSessions_fields() {
        val j = RelayMessageFactory.listSessions("req-1")
        assertEquals("list_sessions", j.getString("action"))
        assertEquals("req-1", j.getString("req_id"))
    }

    @Test fun createSession_payload() {
        val j = RelayMessageFactory.createSession("My Task", "req-2")
        assertEquals("create_session", j.getString("action"))
        assertEquals("My Task", j.getJSONObject("payload").getString("title"))
    }

    @Test fun listDevices_type() {
        assertEquals("list_devices", RelayMessageFactory.listDevices().getString("type"))
    }

    @Test fun listDesktops_type() {
        assertEquals("list_desktops", RelayMessageFactory.listDesktops().getString("type"))
    }

    @Test fun revokeDevice_fields() {
        val j = RelayMessageFactory.revokeDevice("dev-1", "Phone")
        assertEquals("revoke_device", j.getString("type"))
        assertEquals("dev-1", j.getString("device_id"))
        assertEquals("Phone", j.getString("device_name"))
    }

    @Test fun revokeDevice_defaultName() {
        assertEquals("", RelayMessageFactory.revokeDevice("dev-1").getString("device_name"))
    }

    @Test fun renameDeviceById_fields() {
        val j = RelayMessageFactory.renameDeviceById("dev-1", "Old", "New")
        assertEquals("rename_device", j.getString("type"))
        assertEquals("dev-1", j.getString("device_id"))
        assertEquals("Old", j.getString("old_name"))
        assertEquals("New", j.getString("new_name"))
    }

    @Test fun renameDevice_noDeviceId() {
        val j = RelayMessageFactory.renameDevice("Old", "New")
        assertEquals("rename_device", j.getString("type"))
        assertFalse(j.has("device_id"))
    }

    @Test fun fileList_fields() {
        val j = RelayMessageFactory.fileList("/sdcard", "req-3")
        assertEquals("file_list", j.getString("action"))
        assertEquals("req-3", j.getString("req_id"))
        assertEquals("/sdcard", j.getJSONObject("payload").getString("path"))
    }

    @Test fun fileRead_fields() {
        val j = RelayMessageFactory.fileRead("/a/b.txt", "req-4")
        assertEquals("file_read", j.getString("action"))
        assertEquals("/a/b.txt", j.getJSONObject("payload").getString("path"))
    }

    @Test fun diagnose_fields() {
        val j = RelayMessageFactory.diagnose("req-5")
        assertEquals("diagnose", j.getString("action"))
        assertEquals("req-5", j.getString("req_id"))
    }

    @Test fun getConfig_getProjects_actions() {
        assertEquals("get_config", RelayMessageFactory.getConfig("r").getString("action"))
        assertEquals("get_projects", RelayMessageFactory.getProjects("r").getString("action"))
    }

    @Test fun approvalResponse_withNonce() {
        val j = RelayMessageFactory.approvalResponse("call-1", true, "ok", "n-9", "req-6")
        assertEquals("tool_approval_response", j.getString("action"))
        val p = j.getJSONObject("payload")
        assertEquals("call-1", p.getString("call_id"))
        assertTrue(p.getBoolean("approved"))
        assertEquals("ok", p.getString("reason"))
        assertEquals("n-9", p.getString("nonce"))
    }

    @Test fun approvalResponse_noNonce_omitted() {
        val p = RelayMessageFactory.approvalResponse("call-1", false).getJSONObject("payload")
        assertFalse(p.getBoolean("approved"))
        assertFalse(p.has("nonce"))
    }

    @Test fun cancel_fields() {
        val j = RelayMessageFactory.cancel("ses-1", "cm-1", "req-7")
        assertEquals("cancel", j.getString("action"))
        assertEquals("ses-1", j.getString("session_id"))
        assertEquals("cm-1", j.getString("client_msg_id"))
        assertEquals("req-7", j.getString("req_id"))
    }

    @Test fun defaultReqIds_unique() {
        // 默认 req_id 随机生成，两次不应相同
        assertNotEquals(
            RelayMessageFactory.listSessions().getString("req_id"),
            RelayMessageFactory.listSessions().getString("req_id")
        )
    }
}
