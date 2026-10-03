package com.opencode.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.DiffLine
import com.opencode.android.data.model.DiffLineType

@Composable
fun CompactDiffView(
    diffLines: List<DiffLine>,
    rawContent: String? = null,
    modifier: Modifier = Modifier
) {
    var showFullContext by remember { mutableStateOf(false) }

    val addedCount = diffLines.count { it.type == DiffLineType.ADDED }
    val removedCount = diffLines.count { it.type == DiffLineType.REMOVED }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF333333), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // 统计与模式切换头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "改动概览: ",
                    fontSize = 11.5.sp,
                    color = Color.LightGray
                )
                if (addedCount > 0) {
                    Text(
                        text = "+$addedCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4ADE80)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (removedCount > 0) {
                    Text(
                        text = "-$removedCount",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF87171)
                    )
                }
            }

            TextButton(
                onClick = { showFullContext = !showFullContext },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = if (showFullContext) "收起未变动行" else "展开完整文件",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (showFullContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Divider(color = Color(0xFF333333), thickness = 0.8.dp)
        Spacer(modifier = Modifier.height(4.dp))

        // 差分行列表
        if (diffLines.isEmpty() && !rawContent.isNullOrBlank()) {
            val horizontalScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .horizontalScroll(horizontalScroll)
                    .padding(4.dp)
            ) {
                Text(
                    text = rawContent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    lineHeight = 16.sp
                )
            }
        } else {
            // 根据精简模式过滤或显示
            val displayLines = remember(diffLines, showFullContext) {
                if (showFullContext) {
                    diffLines
                } else {
                    // 精简模式：高亮修改行，折叠无变动行（仅保留修改行附近 1 行上下文）
                    compactDiffFilter(diffLines)
                }
            }

            val horizontalScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .horizontalScroll(horizontalScroll)
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(min = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(displayLines) { line ->
                        DiffLineItem(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineItem(line: DiffLine) {
    val bgColor = when (line.type) {
        DiffLineType.ADDED -> Color(0xFF14532D).copy(alpha = 0.65f)
        DiffLineType.REMOVED -> Color(0xFF7F1D1D).copy(alpha = 0.65f)
        DiffLineType.HEADER -> Color(0xFF1E293B)
        DiffLineType.UNCHANGED -> Color.Transparent
    }

    val textColor = when (line.type) {
        DiffLineType.ADDED -> Color(0xFF86EFAC)
        DiffLineType.REMOVED -> Color(0xFFFCA5A5)
        DiffLineType.HEADER -> Color(0xFF93C5FD)
        DiffLineType.UNCHANGED -> Color(0xFFD1D5DB)
    }

    val prefix = when (line.type) {
        DiffLineType.ADDED -> "+"
        DiffLineType.REMOVED -> "-"
        DiffLineType.HEADER -> "@"
        DiffLineType.UNCHANGED -> " "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = prefix,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.width(14.dp)
        )
        Text(
            text = line.content,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            lineHeight = 15.sp
        )
    }
}

/**
 * 精简过滤器：保留修改行及其前后 1 行上下文，多余连续无变动行折叠提示
 */
private fun compactDiffFilter(lines: List<DiffLine>): List<DiffLine> {
    if (lines.isEmpty()) return emptyList()

    val keepIndices = mutableSetOf<Int>()
    for (i in lines.indices) {
        if (lines[i].type == DiffLineType.ADDED || lines[i].type == DiffLineType.REMOVED || lines[i].type == DiffLineType.HEADER) {
            keepIndices.add(i)
            if (i > 0) keepIndices.add(i - 1)
            if (i < lines.size - 1) keepIndices.add(i + 1)
        }
    }

    val result = mutableListOf<DiffLine>()
    var inFoldedGap = false
    var skippedCount = 0

    for (i in lines.indices) {
        if (keepIndices.contains(i)) {
            if (inFoldedGap) {
                result.add(
                    DiffLine(
                        DiffLineType.HEADER,
                        "··· [折叠 $skippedCount 行无变动上下文] ···"
                    )
                )
                inFoldedGap = false
                skippedCount = 0
            }
            result.add(lines[i])
        } else {
            inFoldedGap = true
            skippedCount++
        }
    }

    if (inFoldedGap) {
        result.add(
            DiffLine(
                DiffLineType.HEADER,
                "··· [折叠 $skippedCount 行尾部无变动代码] ···"
            )
        )
    }

    return result
}
