package com.opencode.android.network

import com.opencode.android.data.local.TargetDesktopStore
import com.opencode.android.util.DesktopRoutingPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * v3.1 多 desktop 定向路由单测（纯 JVM，不依赖 Android 框架）。
 * 覆盖：目标选择持久化 round-trip（含 profile 隔离）、会话绑定、
 * 定向路由启用条件、离线提示文案含目标标识。
 */
class DesktopRoutingTest {

    private class MemKv : TargetDesktopStore.Kv {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, def: String): String = map[key] ?: def
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `target desktop round-trip per profile`() {
        val store = TargetDesktopStore(MemKv())
        // 默认未选择（空字符串 = 走主 desktop）
        assertEquals("", store.getTargetDesktopId("p1"))

        store.saveTargetDesktopId("p1", "desk-A")
        assertEquals("desk-A", store.getTargetDesktopId("p1"))

        // profile 隔离：p2 不受影响
        assertEquals("", store.getTargetDesktopId("p2"))
        store.saveTargetDesktopId("p2", "desk-B")
        assertEquals("desk-B", store.getTargetDesktopId("p2"))
        assertEquals("desk-A", store.getTargetDesktopId("p1"))

        // 清空选择 → 回主
        store.saveTargetDesktopId("p1", "")
        assertEquals("", store.getTargetDesktopId("p1"))
    }

    @Test
    fun `session desktop binding round-trip`() {
        val store = TargetDesktopStore(MemKv())
        assertEquals("", store.getSessionBinding("s1"))

        store.saveSessionBinding("s1", "desk-A")
        assertEquals("desk-A", store.getSessionBinding("s1"))

        // 不同会话独立
        assertEquals("", store.getSessionBinding("s2"))
        store.saveSessionBinding("s1", "desk-B")
        assertEquals("desk-B", store.getSessionBinding("s1"))
    }

    @Test
    fun `routing enabled only when flag and server and target`() {
        // 三者齐备才定向
        assertTrue(DesktopRoutingPolicy.shouldRouteToTarget(true, true, "desk-A"))
        // 开关关闭 → 走主
        assertFalse(DesktopRoutingPolicy.shouldRouteToTarget(false, true, "desk-A"))
        // 旧 relay（不支持能力）→ 走主
        assertFalse(DesktopRoutingPolicy.shouldRouteToTarget(true, false, "desk-A"))
        // 未选目标 → 走主
        assertFalse(DesktopRoutingPolicy.shouldRouteToTarget(true, true, ""))

        assertEquals("desk-A", DesktopRoutingPolicy.resolveTarget(true, true, "desk-A"))
        assertNull(DesktopRoutingPolicy.resolveTarget(true, false, "desk-A"))
        assertNull(DesktopRoutingPolicy.resolveTarget(false, true, "desk-A"))
        assertNull(DesktopRoutingPolicy.resolveTarget(true, true, ""))
    }

    @Test
    fun `offline hint contains target identity`() {
        val hint = DesktopRoutingPolicy.offlineHint(
            "书房电脑", "desk-ABC123", "目标电脑（desk-ABC1…）当前不在线",
            "未命名设备", "无服务端消息", "目标电脑「%1\$s」(%2\$s)：%3\$s")
        assertTrue(hint.contains("书房电脑"))
        assertTrue(hint.contains("desk-ABC123"))

        // 无名称时用 device_id 缩写，文案仍含完整标识
        val hint2 = DesktopRoutingPolicy.offlineHint(
            "", "desk-XYZ789", "",
            "未命名设备", "无服务端消息", "目标电脑「%1\$s」(%2\$s)：%3\$s")
        assertTrue(hint2.contains("desk-XYZ789"))
        assertTrue(hint2.contains("desk-XYZ7"))
    }

    @Test
    fun `shortId takes first 8 chars`() {
        assertEquals("desk-ABC", DesktopRoutingPolicy.shortId("desk-ABC123"))
        assertEquals("abc", DesktopRoutingPolicy.shortId("abc"))
    }
}
