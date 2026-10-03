package com.opencode.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P2-11: 原生 Compose Markdown 渲染（零第三方依赖）。
 * 支持：标题、粗体/斜体/删除线、行内代码、围栏代码块（含语言标签+复制按钮）、
 * 无序/有序列表、引用块、链接、分割线、简单表格。
 * 对流式输出中的不完整 Markdown 做了容错（未闭合围栏按代码块渲染）。
 */

// ---------- 数据模型 ----------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class OrderedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    object Hr : MdBlock
}

private data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

// ---------- 行内解析 ----------

private val INLINE_PATTERN = Regex(
    """(\*\*.+?\*\*|\*[^*\n]+?\*|~~.+?~~|`[^`\n]+?`|\[[^\]\n]+\]\([^)\n]+\))"
)

private fun parseInline(text: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var last = 0
    for (m in INLINE_PATTERN.findAll(text)) {
        if (m.range.first > last) {
            spans.add(InlineSpan(text.substring(last, m.range.first)))
        }
        val token = m.value
        when {
            token.startsWith("**") && token.endsWith("**") ->
                spans.add(InlineSpan(token.removeSurrounding("**"), bold = true))
            token.startsWith("*") && token.endsWith("*") ->
                spans.add(InlineSpan(token.removeSurrounding("*"), italic = true))
            token.startsWith("~~") && token.endsWith("~~") ->
                spans.add(InlineSpan(token.removeSurrounding("~~"), strike = true))
            token.startsWith("`") && token.endsWith("`") ->
                spans.add(InlineSpan(token.removeSurrounding("`"), code = true))
            token.startsWith("[") -> {
                val closeIdx = token.indexOf("](")
                if (closeIdx > 0 && token.endsWith(")")) {
                    val label = token.substring(1, closeIdx)
                    val url = token.substring(closeIdx + 2, token.length - 1)
                    spans.add(InlineSpan(label, link = url))
                } else {
                    spans.add(InlineSpan(token))
                }
            }
            else -> spans.add(InlineSpan(token))
        }
        last = m.range.last + 1
    }
    if (last < text.length) {
        spans.add(InlineSpan(text.substring(last)))
    }
    return spans.ifEmpty { listOf(InlineSpan(text)) }
}

// ---------- 块级解析 ----------

private fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.lines()
    var i = 0
    val paragraphBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuf.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraphBuf.joinToString("\n")))
            paragraphBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 围栏代码块（未闭合也容错）
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // 跳过闭合围栏
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        // 分割线
        if (trimmed.matches(Regex("^(---+|\\*\\*\\*+|___+)$"))) {
            flushParagraph()
            blocks.add(MdBlock.Hr)
            i++
            continue
        }

        // 标题
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        // 引用块
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
            continue
        }

        // 简单表格：表头行 + 分隔行
        if (trimmed.startsWith("|") && i + 1 < lines.size &&
            lines[i + 1].trim().matches(Regex("^\\|?[\\s:|-]+\\|?$"))
        ) {
            flushParagraph()
            val header = trimmed.trim('|').split("|").map { it.trim() }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows.add(lines[i].trim().trim('|').split("|").map { it.trim() })
                i++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }

        // 无序列表
        if (trimmed.matches(Regex("^[-*+]\\s+.*"))) {
            flushParagraph()
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().matches(Regex("^[-*+]\\s+.*"))) {
                items.add(lines[i].trim().replaceFirst(Regex("^[-*+]\\s+"), ""))
                i++
            }
            blocks.add(MdBlock.BulletList(items))
            continue
        }

        // 有序列表
        if (trimmed.matches(Regex("^\\d+[.)]\\s+.*"))) {
            flushParagraph()
            val items = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().matches(Regex("^\\d+[.)]\\s+.*"))) {
                items.add(lines[i].trim().replaceFirst(Regex("^\\d+[.)]\\s+"), ""))
                i++
            }
            blocks.add(MdBlock.OrderedList(items))
            continue
        }

        paragraphBuf.add(line)
        i++
    }
    flushParagraph()
    return blocks
}

// ---------- 渲染 ----------

@Composable
private fun InlineText(
    text: String,
    baseColor: Color,
    fontSize: Float = 13.5f,
    onLinkClick: (String) -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val spans = remember(text) { parseInline(text) }
    val annotated = buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                color = if (s.link != null) MaterialTheme.colorScheme.primary else baseColor,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = when {
                    s.strike -> TextDecoration.LineThrough
                    s.link != null -> TextDecoration.Underline
                    else -> null
                },
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) MaterialTheme.colorScheme.surface.copy(alpha = 0.6f) else Color.Transparent,
                fontSize = if (s.code) (fontSize - 0.5f).sp else fontSize.sp
            )
            if (s.link != null) {
                pushStringAnnotation(tag = "URL", annotation = s.link)
            }
            withStyle(style) { append(s.text) }
            if (s.link != null) {
                pop()
            }
        }
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = baseColor,
            fontSize = fontSize.sp,
            lineHeight = 19.sp
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { ann ->
                    try {
                        uriHandler.openUri(ann.item)
                    } catch (e: Exception) {
                        onLinkClick(ann.item)
                    }
                }
        }
    )
}

@Composable
private fun CodeBlockView(code: String, language: String, baseColor: Color) {
    val clipboard = LocalClipboardManager.current
    val highlighted = remember(code, language) { highlightCode(code, language, baseColor) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                RoundedCornerShape(8.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = language.ifBlank { "code" },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制代码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                softWrap = false
            )
        }
    }
}

/**
 * P2-11: 轻量语法高亮（关键字/字符串/注释/数字），支持 kotlin/java/python/js/ts/bash 等。
 * 基于正则的近似高亮，流式场景下足够用。
 */
private fun highlightCode(code: String, language: String, baseColor: Color): AnnotatedString {
    val lang = language.lowercase()
    val keywords = when {
        lang in setOf("kotlin", "kt", "java") ->
            setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "null", "true", "false", "this", "super", "new", "public", "private", "protected", "static", "final", "override", "suspend", "data", "sealed", "companion", "try", "catch", "finally", "throw", "is", "in", "as", "break", "continue", "do", "const", "lateinit", "by", "out", "where")
        lang in setOf("python", "py") ->
            setOf("def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "None", "True", "False", "self", "try", "except", "finally", "raise", "with", "lambda", "pass", "break", "continue", "in", "is", "not", "and", "or", "async", "await", "yield", "global")
        lang in setOf("javascript", "js", "typescript", "ts", "tsx", "jsx") ->
            setOf("function", "const", "let", "var", "class", "if", "else", "for", "while", "return", "import", "from", "export", "default", "new", "this", "null", "undefined", "true", "false", "try", "catch", "finally", "throw", "async", "await", "typeof", "instanceof", "in", "of", "break", "continue", "switch", "case")
        lang in setOf("bash", "sh", "shell", "zsh") ->
            setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "in", "case", "esac", "function", "return", "exit", "echo", "export", "local")
        else -> emptySet()
    }
    if (keywords.isEmpty()) {
        return AnnotatedString(code)
    }
    val keywordColor = Color(0xFF7C4DFF)
    val stringColor = Color(0xFF2E7D32)
    val commentColor = baseColor.copy(alpha = 0.55f)
    val numberColor = Color(0xFFEF6C00)

    return buildAnnotatedString {
        // 按行处理：先剥离注释，再处理字符串/关键字/数字
        val lines = code.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            val commentStart = when {
                lang in setOf("bash", "sh", "shell", "zsh", "python", "py") -> line.indexOf("#")
                else -> line.indexOf("//")
            }
            val codePart = if (commentStart >= 0) line.substring(0, commentStart) else line
            val commentPart = if (commentStart >= 0) line.substring(commentStart) else ""

            // 字符串与普通 token 切分
            val tokenPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|`(?:[^`\\\\]|\\\\.)*`|\\b\\d[\\d._]*\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b|\\s+|.")
            for (m in tokenPattern.findAll(codePart)) {
                val tok = m.value
                when {
                    tok.length >= 2 && ((tok.startsWith("\"") && tok.endsWith("\"")) ||
                            (tok.startsWith("'") && tok.endsWith("'")) ||
                            (tok.startsWith("`") && tok.endsWith("`"))) ->
                        withStyle(SpanStyle(color = stringColor)) { append(tok) }
                    tok.matches(Regex("\\b\\d[\\d._]*\\b")) ->
                        withStyle(SpanStyle(color = numberColor)) { append(tok) }
                    tok in keywords ->
                        withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) { append(tok) }
                    else -> withStyle(SpanStyle(color = baseColor)) { append(tok) }
                }
            }
            if (commentPart.isNotEmpty()) {
                withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) { append(commentPart) }
            }
            if (lineIdx < lines.lastIndex) append("\n")
        }
    }
}

@Composable
private fun TableView(header: List<String>, rows: List<List<String>>, baseColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))) {
            header.forEach { cell ->
                Text(
                    text = cell,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        fontSize = 12.5.sp,
                        color = baseColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

/**
 * P2-11: 原生 Markdown 渲染入口。
 * @param highlightQuery 非空时回退纯文本+高亮（搜索场景）
 */
@Composable
fun MarkdownText(
    markdown: String,
    baseColor: Color,
    highlightQuery: String = "",
    modifier: Modifier = Modifier
) {
    if (highlightQuery.isNotBlank()) {
        // 搜索高亮场景保持原纯文本逻辑
        val annotated = buildAnnotatedString {
            val pattern = runCatching { highlightQuery.toRegex(RegexOption.IGNORE_CASE) }.getOrNull()
            if (pattern == null) {
                append(markdown)
            } else {
                var lastIdx = 0
                pattern.findAll(markdown).forEach { match ->
                    append(markdown.substring(lastIdx, match.range.first))
                    withStyle(SpanStyle(background = Color(0xFFFACC15), color = Color.Black, fontWeight = FontWeight.Bold)) {
                        append(match.value)
                    }
                    lastIdx = match.range.last + 1
                }
                if (lastIdx < markdown.length) append(markdown.substring(lastIdx))
            }
        }
        Text(
            text = annotated,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = baseColor,
            modifier = modifier
        )
        return
    }

    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 18f
                        2 -> 16.5f
                        else -> 15f
                    }
                    InlineText(block.text, baseColor, fontSize = size)
                }
                is MdBlock.Paragraph -> InlineText(block.text, baseColor)
                is MdBlock.CodeBlock -> CodeBlockView(block.code, block.language, baseColor)
                is MdBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("• ", color = baseColor, fontSize = 13.5.sp)
                                InlineText(item, baseColor)
                            }
                        }
                    }
                }
                is MdBlock.OrderedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        block.items.forEachIndexed { n, item ->
                            Row {
                                Text("${n + 1}. ", color = baseColor, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                InlineText(item, baseColor)
                            }
                        }
                    }
                }
                is MdBlock.Quote -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Spacer(
                            modifier = Modifier
                                .width(3.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        InlineText(block.text, baseColor.copy(alpha = 0.85f))
                    }
                }
                is MdBlock.Table -> TableView(block.header, block.rows, baseColor)
                MdBlock.Hr -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(1.dp)
                            .background(baseColor.copy(alpha = 0.25f))
                    )
                }
            }
        }
    }
}

/** 粗略判断文本是否含 Markdown 语法（用于决定是否走渲染） */
fun looksLikeMarkdown(text: String): Boolean {
    if (text.length < 3) return false
    return text.contains("```") ||
        Regex("^#{1,6}\\s", RegexOption.MULTILINE).containsMatchIn(text) ||
        Regex("\\*\\*.+?\\*\\*").containsMatchIn(text) ||
        Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).containsMatchIn(text) ||
        Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE).containsMatchIn(text) ||
        Regex("^\\s*>\\s+", RegexOption.MULTILINE).containsMatchIn(text) ||
        Regex("\\[[^\\]]+\\]\\([^)]+\\)").containsMatchIn(text)
}
