package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState

/**
 * 阶段 1/P0: 发送消息纯变换（从 OpenCodeViewModel.sendMessage 抽出）。
 *
 * 只保留"乐观消息构造 + 列表追加"纯部分；
 * 任务状态/通知/传输调用（relayClient/cloudClient）留在 ViewModel。
 */
object SendMessageReducer {

    /**
     * 构造乐观用户消息。空内容返回 null（调用方直接 return，与原行为一致）。
     * id/now 由调用方传入（原代码用 UUID.randomUUID() 与 ChatMessage 默认时间戳）。
     */
    fun buildUserMessage(content: String, id: String, now: Long): ChatMessage? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        return ChatMessage(
            id = id,
            role = MessageRole.USER,
            content = trimmed,
            timestamp = now
        )
    }

    /** 追加乐观消息：列表截断 + 进入生成态 + 清错误 + 恢复自动滚动 */
    fun appendUserMessage(
        state: OpenCodeUiState,
        msg: ChatMessage,
        maxMessages: Int
    ): OpenCodeUiState {
        val updated = (state.messages + msg).takeLast(maxMessages)
        return state.copy(
            messages = updated,
            isGenerating = true,
            appError = null,
            isAutoScrollPaused = false
        )
    }
}
