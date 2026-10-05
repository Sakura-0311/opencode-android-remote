package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

/**
 * v4.3.2 A3: StreamReducer 单测（纯 JVM）。
 * 覆盖迭代方案 A3 路径 1：流式增量追加与占位消息替换。
 */
class StreamReducerTest {

    private fun msg(id: String, content: String) =
        ChatMessage(id = id, role = MessageRole.ASSISTANT, content = content, isStreaming = true)

    private val fmt: (String, Int, String) -> String =
        { h, n, t -> "$h\n[FOLDED $n]\n$t" }

    @Test
    fun `append hits target message and preserves order`() {
        val messages = listOf(msg("a", "hello "), msg("b", "other"), msg("c", ""))
        val out = StreamReducer.appendToMessage(messages, "a", "world", 2000, fmt)
        assertEquals("hello world", out[0].content)
        assertEquals("other", out[1].content)
        assertEquals("", out[2].content)
        assertEquals(listOf("a", "b", "c"), out.map { it.id })
    }

    @Test
    fun `chunks accumulate in order across calls`() {
        var messages = listOf(msg("a", ""))
        messages = StreamReducer.appendToMessage(messages, "a", "foo", 2000, fmt)
        messages = StreamReducer.appendToMessage(messages, "a", "bar", 2000, fmt)
        messages = StreamReducer.appendToMessage(messages, "a", "baz", 2000, fmt)
        assertEquals("foobarbaz", messages[0].content)
    }

    @Test
    fun `unknown msgId returns same list`() {
        val messages = listOf(msg("a", "x"))
        assertSame(messages, StreamReducer.appendToMessage(messages, "zzz", "y", 2000, fmt))
    }

    @Test
    fun `empty text returns same list`() {
        val messages = listOf(msg("a", "x"))
        assertSame(messages, StreamReducer.appendToMessage(messages, "a", "", 2000, fmt))
    }

    @Test
    fun `collapseIfNeeded no collapse at and below limit`() {
        val three = "l1\nl2\nl3"
        assertTrue(StreamReducer.collapseIfNeeded(three, 3) is StreamReducer.CollapseResult.NoCollapse)
        assertTrue(StreamReducer.collapseIfNeeded(three, 10) is StreamReducer.CollapseResult.NoCollapse)
    }

    @Test
    fun `collapseIfNeeded keeps head 50 and tail maxLines-50`() {
        val lines = (1..120).map { "line$it" }
        val combined = lines.joinToString("\n")
        val r = StreamReducer.collapseIfNeeded(combined, 100)
        assertTrue(r is StreamReducer.CollapseResult.Collapsed)
        r as StreamReducer.CollapseResult.Collapsed
        assertEquals(20, r.hiddenCount) // 120 - 100
        assertEquals((1..50).map { "line$it" }.joinToString("\n"), r.header)
        assertEquals((71..120).map { "line$it" }.joinToString("\n"), r.tail)
    }

    @Test
    fun `appendToMessage applies collapse through formatter`() {
        val lines = (1..120).map { "L$it" }
        val messages = listOf(msg("a", lines.take(90).joinToString("\n")))
        // 再追加 40 行 → 130 行 > 100 上限，触发折叠
        val out = StreamReducer.appendToMessage(
            messages, "a", "\n" + lines.takeLast(40).joinToString("\n"), 100, fmt
        )
        val content = out[0].content
        assertTrue(content.contains("[FOLDED 30]"))
        assertTrue(content.startsWith("L1\nL2"))
        assertTrue(content.endsWith("L119\nL120"))
    }

    @Test
    fun `off-by-one at boundary does not collapse`() {
        val lines = (1..100).map { "x$it" }.joinToString("\n")
        val messages = listOf(msg("a", lines))
        // 正好 100 行 = 上限，不折叠
        val out = StreamReducer.appendToMessage(messages, "a", "", 100, fmt)
        assertSame(messages, out) // 空文本直接返回
        val r = StreamReducer.collapseIfNeeded(lines, 100)
        assertTrue(r is StreamReducer.CollapseResult.NoCollapse)
    }
}
