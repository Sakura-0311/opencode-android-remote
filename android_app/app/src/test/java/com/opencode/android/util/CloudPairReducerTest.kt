package com.opencode.android.util

import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P1: CloudPairReducer 单测（先补测试再拆）。
 */
class CloudPairReducerTest {

    private val base = OpenCodeUiState()

    private fun sess(id: String, updatedAt: Long = 1000L) =
        SessionItem(id = id, title = "t-$id", tag = "default", updatedAt = updatedAt)

    @Test fun healthCheckStarted_setsBanner() {
        val out = CloudPairReducer.applyHealthCheckStarted(base, "检查中…")
        assertEquals("检查中…", out.statusBanner)
    }

    @Test fun saveFailed_secureStorageUnavailable() {
        val out = CloudPairReducer.applySaveFailed(base.copy(statusBanner = "x"), "SEC")
        assertEquals("SECURE_STORAGE_UNAVAILABLE", out.appError?.code)
        assertEquals("SEC", out.appError?.message)
        assertNull(out.statusBanner)
    }

    @Test fun success_switchesToCloudMode() {
        val out = CloudPairReducer.applySuccess(base, "https://h", "k", "/w")
        assertEquals(AppMode.CLOUD_HOSTED, out.appMode)
        assertEquals("https://h", out.cloudServerUrl)
        assertEquals("k", out.cloudApiKey)
        assertEquals("/w", out.cloudWorkspacePath)
        assertTrue(out.isPaired)
        assertTrue(out.isAuthenticated)
        assertTrue(out.isRelayConnected)
        assertNull(out.appError)
        assertNull(out.diagnostics)
        assertNull(out.statusBanner)
        assertFalse(base.isPaired) // 不变性
    }

    @Test fun healthFailed_setsCloudCheckFailed() {
        val out = CloudPairReducer.applyHealthFailed(base.copy(isPaired = true), "timeout")
        assertFalse(out.isPaired)
        assertEquals("CLOUD_CHECK_FAILED", out.appError?.code)
        assertEquals("timeout", out.appError?.message)
        assertNull(out.statusBanner)
    }

    @Test fun sessionsLoaded_sortsAndSelectsFirst() {
        val sessions = listOf(sess("b", 1000L), sess("a", 2000L))
        val out = CloudPairReducer.applySessionsLoaded(base, sessions)
        assertEquals(listOf("a", "b"), out.availableSessions.map { it.id }) // 更新时间倒序
        // currentSessionId 取未排序首个（与原逻辑一致）
        assertEquals("b", out.currentSessionId)
    }

    @Test fun sessionsLoaded_emptyClearsCurrent() {
        val state = base.copy(currentSessionId = "old")
        val out = CloudPairReducer.applySessionsLoaded(state, emptyList())
        assertTrue(out.availableSessions.isEmpty())
        assertEquals("", out.currentSessionId)
    }
}
