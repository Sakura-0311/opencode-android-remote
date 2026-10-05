package com.opencode.android.util

import org.junit.Assert.*
import org.junit.Test

/**
 * v4.4.0 A1+A2: StreamWindow 单测（纯 JVM）。
 * 用例来自 docx：6000 行 hidden=4000；半行切分；CRLF；恰好 2000 行不折叠；
 * 折叠后继续追加仍累计。
 */
class StreamWindowTest {

    private val fmt: (String, Int, String) -> String =
        { header, hidden, tail -> "H[$header]...$hidden...T[$tail]" }

    @Test fun append6000Lines_hiddenIs4000() {
        val w = StreamWindow()
        w.append((1..6000).joinToString("\n") { "line$it" } + "\n")
        assertEquals(6000, w.totalLines)
        assertEquals(4000, w.hidden)
        val rendered = w.render(fmt)
        assertTrue(rendered.startsWith("H[line1"))
        assertTrue(rendered.contains("...4000..."))
        assertTrue(rendered.endsWith("line6000]"))
        // 头 50 行是 line1..line50，尾是 line4051..line6000
        assertTrue(rendered.contains("line50]"))
        assertFalse(rendered.contains("line51\n"))
    }

    @Test fun chunkSplitMidLine_reassembled() {
        val w = StreamWindow()
        w.append("hel")
        w.append("lo\nwor")
        w.append("ld\n")
        assertEquals(2, w.totalLines)
        assertEquals("hello\nworld", w.render(fmt))
    }

    @Test fun crlf_stripped() {
        val w = StreamWindow()
        w.append("a\r\nb\r\n")
        assertEquals("a\nb", w.render(fmt))
    }

    @Test fun exactly2000Lines_noCollapse() {
        val w = StreamWindow()
        w.append((1..2000).joinToString("\n") { "l$it" } + "\n")
        assertEquals(0, w.hidden)
        val rendered = w.render(fmt)
        assertFalse(rendered.contains("..."))
        assertTrue(rendered.startsWith("l1\n"))
        assertTrue(rendered.endsWith("l2000"))
    }

    @Test fun continueAppendingAfterCollapse_stillAccumulates() {
        val w = StreamWindow()
        w.append((1..3000).joinToString("\n") { "l$it" } + "\n")
        assertEquals(1000, w.hidden)
        w.append((3001..5000).joinToString("\n") { "l$it" } + "\n")
        assertEquals(5000, w.totalLines)
        assertEquals(3000, w.hidden)
        val rendered = w.render(fmt)
        assertTrue(rendered.contains("...3000..."))
        assertTrue(rendered.endsWith("l5000]"))
    }

    @Test fun partialLine_includedInRender() {
        val w = StreamWindow()
        w.append("incomplete")
        assertTrue(w.isEmpty == false)
        assertEquals("incomplete", w.render(fmt))
        assertEquals(0, w.totalLines) // 半行不计入完整行
    }

    @Test fun clear_resets() {
        val w = StreamWindow()
        w.append("a\nb\n")
        w.clear()
        assertTrue(w.isEmpty)
        assertEquals(0, w.totalLines)
        assertEquals("", w.render(fmt))
    }

    @Test fun emptyChunk_noop() {
        val w = StreamWindow()
        w.append("")
        assertTrue(w.isEmpty)
    }
}
