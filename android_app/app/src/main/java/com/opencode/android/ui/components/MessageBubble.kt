package com.opencode.android.ui.components

import com.opencode.android.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.*
import com.opencode.android.network.CloudConnectionState
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.ui.components.ToolApprovalDialog
import com.opencode.android.ui.components.MarkdownText
import com.opencode.android.ui.components.looksLikeMarkdown
import com.opencode.android.util.MarkdownExporter
import com.opencode.android.util.ErrorCodes
import com.opencode.android.util.TAG_ALL
import com.opencode.android.util.TAG_DEFAULT
import com.opencode.android.util.TAG_KEY_TO_RES
import kotlinx.coroutines.launch

@Composable
fun MessageBubbleWithHighlight(message: ChatMessage, appMode: AppMode, highlightQuery: String) {
    val isUser = message.role == MessageRole.USER
    val isError = message.isError

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                isError -> Icons.Default.Error
                                appMode == AppMode.CLOUD_HOSTED -> Icons.Default.Cloud
                                else -> Icons.Default.SmartToy
                            },
                            contentDescription = null,
                            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isError -> stringResource(R.string.chat_035)
                                appMode == AppMode.CLOUD_HOSTED -> "OpenCode Cloud"
                                else -> "OpenCode Desktop"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                SelectionContainer {
                    val rawText = message.content.ifEmpty { if (message.isStreaming) "▌" else "..." }
                    // 流式中用纯文本渲染：每 50ms 全量 Markdown 解析太贵，流结束后再走 Markdown
                    val useMarkdown = !isUser && !isError && !message.isStreaming && highlightQuery.isBlank() &&
                        remember(rawText) { looksLikeMarkdown(rawText) }
                    if (useMarkdown) {
                        // P2-11: AI 回复走原生 Markdown 渲染
                        MarkdownText(
                            markdown = rawText,
                            baseColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                    val annotatedText = buildAnnotatedString {
                        if (highlightQuery.isBlank()) {
                            append(rawText)
                        } else {
                            val pattern = highlightQuery.toRegex(RegexOption.IGNORE_CASE)
                            var lastIdx = 0
                            pattern.findAll(rawText).forEach { match ->
                                append(rawText.substring(lastIdx, match.range.first))
                                withStyle(SpanStyle(background = Color(0xFFFACC15), color = Color.Black, fontWeight = FontWeight.Bold)) {
                                    append(match.value)
                                }
                                lastIdx = match.range.last + 1
                            }
                            if (lastIdx < rawText.length) {
                                append(rawText.substring(lastIdx))
                            }
                        }
                    }

                    Text(
                        text = annotatedText,
                        fontSize = 13.5.sp,
                        fontFamily = if (!isUser) FontFamily.Monospace else FontFamily.Default,
                        lineHeight = 19.sp,
                        color = when {
                            isError -> MaterialTheme.colorScheme.onErrorContainer
                            isUser -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    } // P2-11: useMarkdown=false 分支结束
                }

                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appMode == AppMode.CLOUD_HOSTED) stringResource(R.string.chat_036) else stringResource(R.string.chat_037),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
