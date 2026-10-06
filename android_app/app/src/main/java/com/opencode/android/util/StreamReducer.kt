package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage

/**
 * v4.3.2 A3: 流式输出的纯归约逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * v5.0.3 (C-6): 删掉 appendToMessage 与 collapseIfNeeded——它们只服务于
 * ENABLE_STREAM_WINDOW=false 的旧 StringBuilder 路径，该分支已恒不可达。
 * 现在唯一的流式路径是 StreamWindow 渲染 + 整体替换 content。
 */
object StreamReducer {

    /**
     * 用窗口渲染出的完整文本直接替换指定消息的 content。
     * - 命中：仅该条消息的 content 变化；未命中返回原列表（引用不变）。
     */
    fun replaceMessageContent(
        messages: List<ChatMessage>,
        msgId: String,
        content: String
    ): List<ChatMessage> {
        var hit = false
        val updated = messages.map { msg ->
            if (msg.id == msgId) {
                hit = true
                msg.copy(content = content)
            } else {
                msg
            }
        }
        return if (hit) updated else messages
    }
}
