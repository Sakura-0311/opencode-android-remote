package com.opencode.android.util

import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem

/**
 * 阶段 1/P1: 云端配对纯状态变换（从 OpenCodeViewModel.pairCloud 抽出）。
 *
 * 探活回调（cloudClient.checkHealth）、配置落盘（prefsManager）留在 ViewModel；
 * 这里只做各阶段的 UiState 映射。
 */
object CloudPairReducer {

    /** 探活开始：banner 提示 */
    fun applyHealthCheckStarted(state: OpenCodeUiState, banner: String): OpenCodeUiState =
        state.copy(statusBanner = banner)

    /** 配置落盘失败：加密存储不可用 */
    fun applySaveFailed(state: OpenCodeUiState, secureUnavailableMessage: String): OpenCodeUiState =
        state.copy(
            appError = AppError("SECURE_STORAGE_UNAVAILABLE", secureUnavailableMessage),
            statusBanner = null
        )

    /** 探活 + 落盘成功：切换云端模式 */
    fun applySuccess(
        state: OpenCodeUiState,
        url: String,
        apiKey: String,
        workspacePath: String
    ): OpenCodeUiState = state.copy(
        appMode = AppMode.CLOUD_HOSTED,
        cloudServerUrl = url,
        cloudApiKey = apiKey,
        cloudWorkspacePath = workspacePath,
        isPaired = true,
        isAuthenticated = true,
        isRelayConnected = true,
        appError = null,
        diagnostics = null,
        statusBanner = null
    )

    /** 探活失败 */
    fun applyHealthFailed(state: OpenCodeUiState, message: String): OpenCodeUiState =
        state.copy(
            isPaired = false,
            statusBanner = null,
            appError = AppError("CLOUD_CHECK_FAILED", message)
        )

    /**
     * 会话列表拉取完成：排序 + 选中首个。
     * 注意：currentSessionId 取未排序列表的首个（与原 pairCloud 内联逻辑一致），
     * availableSessions 取排序后的列表。
     */
    fun applySessionsLoaded(
        state: OpenCodeUiState,
        sessions: List<SessionItem>
    ): OpenCodeUiState {
        return state.copy(
            availableSessions = SessionReducer.sortSessions(sessions),
            currentSessionId = sessions.firstOrNull()?.id ?: ""
        )
    }
}
