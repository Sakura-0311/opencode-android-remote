package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage

/**
 * v4.3.2 A3: 流式输出的纯归约逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * 从 OpenCodeViewModel 抽取，原内联逻辑行为不变：
 * - flushStreamBuffer 的消息追加语义：按 id 找到流式消息并追加文本，其余消息原样保留；
 * - applyStreamGuard 的超长折叠：超过 maxLines 行时保留头 50 行 + 尾 (maxLines-50) 行。
 */
object StreamReducer {

    /** 折叠判定的纯结果：不折叠，或折叠为（头，隐藏行数，尾） */
    sealed interface CollapseResult {
        data object NoCollapse : CollapseResult
        data class Collapsed(val header: String, val hiddenCount: Int, val tail: String) : CollapseResult
    }

    /**
     * 超长输出折叠判定。纯字符串逻辑；调用方负责把 Collapsed 格式化为最终文案
     *（生产代码用 R.string.vm_028，测试用任意格式）。
     */
    fun collapseIfNeeded(combined: String, maxLines: Int): CollapseResult {
        val lines = combined.split("\n")
        if (lines.size <= maxLines) return CollapseResult.NoCollapse
        val header = lines.take(50).joinToString("\n")
        val tail = lines.takeLast(maxLines - 50).joinToString("\n")
        return CollapseResult.Collapsed(header, lines.size - maxLines, tail)
    }

    /**
     * 把 text 追加到指定 id 的消息 content 末尾。
     * - 命中：仅该条消息的 content 变化（含折叠），其余消息与顺序不变；
     * - 未命中（msgId 不存在）：返回原列表（引用不变），调用方可据此跳过 UI 更新。
     */
    fun appendToMessage(
        messages: List<ChatMessage>,
        msgId: String,
        text: String,
        maxLines: Int,
        formatCollapsed: (header: String, hiddenCount: Int, tail: String) -> String
    ): List<ChatMessage> {
        if (text.isEmpty()) return messages
        var hit = false
        val updated = messages.map { msg ->
            if (msg.id == msgId) {
                hit = true
                val combined = msg.content + text
                val content = when (val r = collapseIfNeeded(combined, maxLines)) {
                    is CollapseResult.NoCollapse -> combined
                    is CollapseResult.Collapsed -> formatCollapsed(r.header, r.hiddenCount, r.tail)
                }
                msg.copy(content = content)
            } else {
                msg
            }
        }
        return if (hit) updated else messages
    }

    /**
     * 用窗口渲染出的完整文本直接替换指定消息的 content。
     * 供 StreamWindow 路径使用（不再做增量拼接+重折叠）。
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
