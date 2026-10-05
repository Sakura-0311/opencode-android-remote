package com.opencode.android.coordinator

import com.opencode.android.R
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.DiagnosticsResult

/**
 * v4.8.0/M8: 诊断与日志视图状态从 OpenCodeViewModel 抽出。
 * 纯状态调度 + 单一副作用入口（diagnose），单测用假 StateDispatcher 覆盖。
 */
class DiagnosticsCoordinator(
    private val dispatch: StateDispatcher,
    private val diagnose: (targetUrl: String, key: String, cb: (DiagnosticsResult) -> Unit) -> Unit,
) {
    fun testConnectivity() {
        val s = dispatch.currentState
        val targetUrl = if (s.appMode == AppMode.CLOUD_HOSTED) s.cloudServerUrl else s.relayUrl
        val key = if (s.appMode == AppMode.CLOUD_HOSTED) s.cloudApiKey else s.secret
        dispatch.updateState {
            it.copy(diagnostics = DiagnosticsResult(
                isChecking = true,
                statusTitle = dispatch.getString(R.string.vm_005)
            ))
        }
        diagnose(targetUrl, key) { result ->
            dispatch.updateState { it.copy(diagnostics = result) }
        }
    }

    fun clearDiagnostics() {
        dispatch.updateState { it.copy(diagnostics = null) }
    }

    fun setLogSearchQuery(query: String) {
        dispatch.updateState { it.copy(logSearchQuery = query) }
    }

    fun setAutoScrollPaused(isPaused: Boolean) {
        dispatch.updateState { it.copy(isAutoScrollPaused = isPaused) }
    }
}
