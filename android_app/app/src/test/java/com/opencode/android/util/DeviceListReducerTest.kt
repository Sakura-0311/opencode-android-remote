package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.DesktopInfo
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P1: DeviceListReducer 单测（先补测试再拆）。
 */
class DeviceListReducerTest {

    private val base = OpenCodeUiState()

    private fun desk(id: String, primary: Boolean = false) =
        DesktopInfo(deviceId = id, deviceName = "n-$id", isPrimary = primary)

    // ---- resolveTarget ----

    @Test fun resolveTarget_emptyPrevTarget_staysEmptyAndAlive() {
        val (alive, target) = DeviceListReducer.resolveTarget("", listOf(desk("a")))
        assertTrue(alive)
        assertEquals("", target)
    }

    @Test fun resolveTarget_targetAlive_kept() {
        val (alive, target) = DeviceListReducer.resolveTarget("a", listOf(desk("a"), desk("b")))
        assertTrue(alive)
        assertEquals("a", target)
    }

    @Test fun resolveTarget_targetGone_cleared() {
        val (alive, target) = DeviceListReducer.resolveTarget("zzz", listOf(desk("a")))
        assertFalse(alive)
        assertEquals("", target)
    }

    @Test fun resolveTarget_emptyList_clearsNonEmpty() {
        val (alive, target) = DeviceListReducer.resolveTarget("a", emptyList())
        assertFalse(alive)
        assertEquals("", target)
    }

    // ---- applyDesktopList ----

    @Test fun applyDesktopList_targetGone_fallsBackToPrimary() {
        val state = base.copy(targetDesktopId = "gone")
        val out = DeviceListReducer.applyDesktopList(state, listOf(desk("a", primary = true)))
        assertEquals(listOf("a"), out.desktopList.map { it.deviceId })
        assertEquals("", out.targetDesktopId)
        assertEquals("gone", state.targetDesktopId) // 不变性
    }

    @Test fun applyDesktopList_targetAlive_kept() {
        val state = base.copy(targetDesktopId = "b")
        val out = DeviceListReducer.applyDesktopList(state, listOf(desk("a"), desk("b")))
        assertEquals("b", out.targetDesktopId)
    }

    // ---- selectE2eeTarget ----

    @Test fun selectE2eeTarget_explicitWins() {
        assertEquals("x", DeviceListReducer.selectE2eeTarget("x", listOf(desk("a", primary = true))))
    }

    @Test fun selectE2eeTarget_emptyFallsBackToPrimary() {
        val list = listOf(desk("a"), desk("b", primary = true))
        assertEquals("b", DeviceListReducer.selectE2eeTarget("", list))
    }

    @Test fun selectE2eeTarget_noPrimary_returnsEmpty() {
        assertEquals("", DeviceListReducer.selectE2eeTarget("", listOf(desk("a"))))
        assertEquals("", DeviceListReducer.selectE2eeTarget("", emptyList()))
    }
}
