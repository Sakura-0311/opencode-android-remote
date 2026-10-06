package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

/**
 * v4.3.2 A3: StreamReducer 单测（纯 JVM）。
 * v5.0.3 (C-6): appendToMessage / collapseIfNeeded 随旧流式路径一起删除
 * （ENABLE_STREAM_WINDOW 恒为 true，那条分支不可达），此处只留唯一在用的
 * replaceMessageContent。
 */
class StreamReducerTest {

    private fun msg(id: String, content: String) =
        ChatMessage(id = id, role = MessageRole.ASSISTANT, content = content, isStreaming = true)

    @Test
    fun `replace updates target and preserves order`() {
        val messages = listOf(msg("a", "hello"), msg("b", "other"), msg("c", ""))
        val out = StreamReducer.replaceMessageContent(messages, "a", "hello world")
        assertEquals("hello world", out[0].content)
        assertEquals("other", out[1].content)
        assertEquals("", out[2].content)
        assertEquals(listOf("a", "b", "c"), out.map { it.id })
    }

    @Test
    fun `replace accumulates window render across calls`() {
        var messages = listOf(msg("a", ""))
        messages = StreamReducer.replaceMessageContent(messages, "a", "foo")
        messages = StreamReducer.replaceMessageContent(messages, "a", "foobar")
        messages = StreamReducer.replaceMessageContent(messages, "a", "foobarbaz")
        assertEquals("foobarbaz", messages[0].content)
    }

    @Test
    fun `unknown msgId returns same list`() {
        val messages = listOf(msg("a", "x"))
        assertSame(messages, StreamReducer.replaceMessageContent(messages, "zzz", "y"))
    }

    @Test
    fun `empty content is still written`() {
        val messages = listOf(msg("a", "x"))
        val out = StreamReducer.replaceMessageContent(messages, "a", "")
        assertEquals("", out[0].content)
    }

    @Test
    fun `streaming flag is preserved`() {
        val messages = listOf(msg("a", "x"))
        val out = StreamReducer.replaceMessageContent(messages, "a", "y")
        assertTrue(out[0].isStreaming)
    }
}