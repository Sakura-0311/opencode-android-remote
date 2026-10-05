package com.opencode.android.util

/**
 * 流式输出的头尾窗口。
 *
 * 替代每次 flush 对全文做 `msg.content + text` + `split("\n")` 再重折叠的做法——
 * 旧做法有两个问题：
 * 1. 折叠提示行数不准：已折叠文本（含"…省略 N 行…"提示行）被重新计入行数，
 *    每轮只统计本轮新增隐藏行，旧提示的计数被丢弃；
 * 2. 性能接近平方：每 50ms 对全文拼接 + split，成本随输出变长增长。
 *
 * 本类只处理增量：
 * - append(chunk) 按 \n 切分，半行留在 [partial] 缓冲等下一块；
 * - 完整行先填满 head（前 50 行），之后进 tail 队列（最多 1950 行），
 *   溢出的计入 hiddenCount；
 * - render() 在需要时才拼出最终文本。
 */
class StreamWindow(
    private val headMax: Int = 50,
    private val tailMax: Int = 1950,
) {
    private val headLines = ArrayList<String>(headMax)
    private val tailLines = ArrayDeque<String>()
    private var hiddenCount = 0
    private val partial = StringBuilder()

    /** 完整行总数（含隐藏）。 */
    val totalLines: Int get() = headLines.size + tailLines.size + hiddenCount

    /** 隐藏行数（折叠提示用）。 */
    val hidden: Int get() = hiddenCount

    /** 是否没有任何内容（含半行）。 */
    val isEmpty: Boolean get() = totalLines == 0 && partial.isEmpty()

    /** 追加一块流式文本，只处理增量。CRLF 的 \r 会被剥离。 */
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        partial.append(chunk)
        var start = 0
        while (true) {
            val nl = partial.indexOf("\n", start)
            if (nl < 0) break
            var line = partial.substring(start, nl)
            if (line.endsWith("\r")) line = line.dropLast(1)
            pushLine(line)
            start = nl + 1
        }
        partial.delete(0, start)
    }

    private fun pushLine(line: String) {
        if (headLines.size < headMax) {
            headLines.add(line)
            return
        }
        tailLines.addLast(line)
        if (tailLines.size > tailMax) {
            tailLines.removeFirst()
            hiddenCount++
        }
    }

    fun clear() {
        headLines.clear()
        tailLines.clear()
        hiddenCount = 0
        partial.clear()
    }

    /**
     * 拼出当前应显示的文本。
     * - 未超限：直接返回全部行（含未完成的半行），不调用 fmt；
     * - 超限：fmt(头文本, 隐藏行数, 尾文本)，半行（若有）追加在尾后。
     */
    fun render(fmt: (header: String, hidden: Int, tail: String) -> String): String {
        val max = headMax + tailMax
        return if (totalLines <= max) {
            (headLines + tailLines + listOf(partial.toString()).filter { it.isNotEmpty() })
                .joinToString("\n")
        } else {
            val collapsed = fmt(
                headLines.joinToString("\n"),
                hiddenCount,
                tailLines.joinToString("\n")
            )
            if (partial.isNotEmpty()) "$collapsed\n$partial" else collapsed
        }
    }
}
