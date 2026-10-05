package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.CloudConnectionState

/**
 * 阶段 1/P1: 流结束纯变换（从 OpenCodeViewModel.onStreamEnd 抽出）。
 *
 * 通知栏（OpenCodeKeepAliveService）、任务状态（setTaskStatus）留在 ViewModel；
 * 这里只做消息收尾 + 时长计算。
 */
object StreamEndReducer {

    /** 把流式中的消息标记为完成；activeId 为 null 时原样返回 */
    fun finalizeMessages(
        messages: List<ChatMessage>,
        activeId: String?
    ): List<ChatMessage> =
        messages.map { msg ->
            if (activeId != null && msg.id == activeId) msg.copy(isStreaming = false)
            else msg
        }

    /** onStreamEnd 的纯状态部分 */
    fun applyStreamEnd(state: OpenCodeUiState, activeId: String?): OpenCodeUiState =
        state.copy(
            cloudConnectionState = CloudConnectionState.DISCONNECTED,
            messages = finalizeMessages(state.messages, activeId),
            isGenerating = false
        )

    /** 任务耗时：未计时（<=0）时返回 0 */
    fun computeDurationMs(taskStartTimeMs: Long, now: Long): Long =
        if (taskStartTimeMs > 0) now - taskStartTimeMs else 0L
}
