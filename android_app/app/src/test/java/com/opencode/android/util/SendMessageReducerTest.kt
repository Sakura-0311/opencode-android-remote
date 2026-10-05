package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P0: SendMessageReducer 单测（先补测试再拆）。
 */
class SendMessageReducerTest {

    private val base = OpenCodeUiState()

    private fun msg(id: String, content: String) =
        ChatMessage(id = id, role = MessageRole.USER, content = content)

    // ---- buildUserMessage ----

    @Test fun build_blankReturnsNull() {
        assertNull(SendMessageReducer.buildUserMessage("   ", "id1", 1000L))
        assertNull(SendMessageReducer.buildUserMessage("", "id1", 1000L))
    }

    @Test fun build_trimsContent() {
        val m = SendMessageReducer.buildUserMessage("  hello  ", "id1", 1000L)!!
        assertEquals("hello", m.content)
        assertEquals("id1", m.id)
        assertEquals(MessageRole.USER, m.role)
        assertEquals(1000L, m.timestamp)
    }

    @Test fun build_preservesInternalSpaces() {
        val m = SendMessageReducer.buildUserMessage("a  b", "id1", 1000L)!!
        assertEquals("a  b", m.content)
    }

    // ---- appendUserMessage ----

    @Test fun append_setsGeneratingClearsError() {
        val withErr = base.copy(appError = com.opencode.android.data.model.AppError("X", "y"),
            isAutoScrollPaused = true)
        val out = SendMessageReducer.appendUserMessage(withErr, msg("m1", "hi"), 500)
        assertEquals(1, out.messages.size)
        assertEquals("hi", out.messages[0].content)
        assertTrue(out.isGenerating)
        assertNull(out.appError)
        assertFalse(out.isAutoScrollPaused)
        // 原对象不变
        assertTrue(withErr.isAutoScrollPaused)
    }

    @Test fun append_respectsMaxCount() {
        val many = (1..10).map { msg("m$it", "c$it") }
        val state = base.copy(messages = many)
        val out = SendMessageReducer.appendUserMessage(state, msg("new", "n"), 5)
        assertEquals(5, out.messages.size)
        assertEquals("new", out.messages.last().content)
        assertEquals("m7", out.messages.first().content) // takeLast 语义
    }

    @Test fun append_doesNotMutateInput() {
        val original = listOf(msg("m1", "a"))
        val state = base.copy(messages = original)
        SendMessageReducer.appendUserMessage(state, msg("m2", "b"), 500)
        assertEquals(1, original.size)
        assertEquals(1, state.messages.size)
    }
}
