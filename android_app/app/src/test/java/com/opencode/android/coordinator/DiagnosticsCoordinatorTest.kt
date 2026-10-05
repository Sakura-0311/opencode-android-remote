package com.opencode.android.coordinator

import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.DiagnosticsResult
import com.opencode.android.data.model.OpenCodeUiState
import org.junit.Assert.*
import org.junit.Test

/**
 * v4.8.0/M8: DiagnosticsCoordinator 单测（纯 JVM）。
 */
class DiagnosticsCoordinatorTest {

    private fun coordinator(
        state: OpenCodeUiState = OpenCodeUiState(),
        diagnose: (String, String, (DiagnosticsResult) -> Unit) -> Unit = { _, _, cb ->
            cb(DiagnosticsResult(isChecking = false, statusTitle = "ok"))
        },
    ): Pair<DiagnosticsCoordinator, FakeStateDispatcher> {
        val dispatch = FakeStateDispatcher(state)
        return DiagnosticsCoordinator(dispatch, diagnose) to dispatch
    }

    @Test
    fun `testConnectivity targets relay url in desktop mode`() {
        var gotUrl: String? = null
        var gotKey: String? = null
        val (c, d) = coordinator(
            state = OpenCodeUiState(appMode = AppMode.DESKTOP_RELAY, relayUrl = "wss://r/x", secret = "s3cr3t"),
            diagnose = { url, key, cb -> gotUrl = url; gotKey = key; cb(DiagnosticsResult(isChecking = false, statusTitle = "ok")) },
        )
        c.testConnectivity()
        assertEquals("wss://r/x", gotUrl)
        assertEquals("s3cr3t", gotKey)
        // 先 checking=true，再写入结果
        assertTrue(d.updates[0].diagnostics!!.isChecking)
        assertEquals("ok", d.currentState.diagnostics!!.statusTitle)
    }

    @Test
    fun `testConnectivity targets cloud url in cloud mode`() {
        var gotUrl: String? = null
        val (c, _) = coordinator(
            state = OpenCodeUiState(
                appMode = AppMode.CLOUD_HOSTED,
                cloudServerUrl = "https://cloud/x", cloudApiKey = "k",
            ),
            diagnose = { url, key, cb -> gotUrl = url; cb(DiagnosticsResult(isChecking = false, statusTitle = "ok")) },
        )
        c.testConnectivity()
        assertEquals("https://cloud/x", gotUrl)
    }

    @Test
    fun `clearDiagnostics nulls result`() {
        val (c, d) = coordinator(state = OpenCodeUiState(diagnostics = DiagnosticsResult(isChecking = false, statusTitle = "x")))
        c.clearDiagnostics()
        assertNull(d.currentState.diagnostics)
    }

    @Test
    fun `log search and autoscroll update state`() {
        val (c, d) = coordinator()
        c.setLogSearchQuery("err")
        c.setAutoScrollPaused(true)
        assertEquals("err", d.currentState.logSearchQuery)
        assertTrue(d.currentState.isAutoScrollPaused)
    }
}
