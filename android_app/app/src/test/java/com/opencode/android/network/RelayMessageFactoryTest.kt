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

    @Test fun diagnose_fields() {
        val j = RelayMessageFactory.diagnose("req-5")
        assertEquals("diagnose", j.getString("action"))
        assertEquals("req-5", j.getString("req_id"))
    }

    @Test fun getConfig_getProjects_actions() {
        assertEquals("get_config", RelayMessageFactory.getConfig("r").getString("action"))
        assertEquals("get_projects", RelayMessageFactory.getProjects("r").getString("action"))
    }

    @Test fun defaultReqIds_unique() {
        // 默认 req_id 随机生成，两次不应相同
        assertNotEquals(
            RelayMessageFactory.listSessions().getString("req_id"),
            RelayMessageFactory.listSessions().getString("req_id")
        )
    }
}
