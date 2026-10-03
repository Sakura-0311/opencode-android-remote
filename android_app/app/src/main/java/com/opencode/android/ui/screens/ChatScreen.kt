package com.opencode.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: OpenCodeUiState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    onClearChat: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息或流式输出自动滚底
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (uiState.appMode == AppMode.CLOUD_HOSTED) "OpenCode Cloud" else "OpenCode Remote",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // 状态圆点
                                val dotColor = when {
                                    uiState.appMode == AppMode.CLOUD_HOSTED && uiState.isAuthenticated -> Color(0xFF10B981) // 云端就绪
                                    uiState.isDesktopOnline && uiState.isAuthenticated -> Color(0xFF10B981) // 电脑在线
                                    uiState.isRelayConnected -> Color(0xFFF59E0B) // 中继已连
                                    else -> Color(0xFFEF4444)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                            }
                            val statusText = if (uiState.appMode == AppMode.CLOUD_HOSTED) {
                                "☁️ 云端工作区已就绪 · ${uiState.cloudWorkspacePath}"
                            } else {
                                when {
                                    uiState.isDesktopOnline && uiState.isAuthenticated -> "💻 电脑在线 · 已安全鉴权 (${uiState.accountId})"
                                    uiState.isRelayConnected && !uiState.isAuthenticated -> "正在进行密钥握手..."
                                    uiState.isRelayConnected -> "已连中继 · 等待桌面代理接入..."
                                    uiState.isReconnecting -> "连接中断 · 正在自动重试..."
                                    else -> "中继未连通"
                                }
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onClearChat) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Chat")
                        }
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.Default.Logout, contentDescription = "Disconnect")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // 顶部红色错误横幅
                if (uiState.appError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "[${uiState.appError.code}] ${uiState.appError.message}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 重连中 / 状态横幅
                if (uiState.statusBanner != null && uiState.appError == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.statusBanner,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(bottom = 8.dp)
            ) {
                // 快捷提示 Chip
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val suggestions = if (uiState.appMode == AppMode.CLOUD_HOSTED) {
                        listOf("克隆 Git 仓库", "查看工作区文件", "运行项目构建", "运行单元测试", "/help")
                    } else {
                        listOf("查看当前 Git 变更", "运行单元测试", "解释当前模块实现", "/help", "/compact")
                    }
                    items(suggestions) { text ->
                        SuggestionChip(
                            onClick = { onSendMessage(text) },
                            label = { Text(text, fontSize = 12.sp) },
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }

                // 输入框与发送/取消按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            val hint = if (uiState.appMode == AppMode.CLOUD_HOSTED)
                                "给云端 OpenCode 下达指令..."
                            else
                                "给电脑端的 OpenCode 下达指令..."
                            Text(hint)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 4
                    )

                    if (uiState.isGenerating) {
                        FloatingActionButton(
                            onClick = onCancel,
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    } else {
                        FloatingActionButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    val textToSend = inputText
                                    inputText = ""
                                    onSendMessage(textToSend)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            if (uiState.messages.isEmpty()) {
                // 空状态欢迎界面
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (uiState.appMode == AppMode.CLOUD_HOSTED) Icons.Default.CloudQueue else Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (uiState.appMode == AppMode.CLOUD_HOSTED) "已连入云端 OpenCode 工作区" else "已连接到专属电脑桥接通道",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (uiState.appMode == AppMode.CLOUD_HOSTED)
                            "云端独立运行模式，无需电脑开机。在下方输入 prompt 即可在云端沙盒自动写代码、跑测试。"
                        else if (uiState.isDesktopOnline && uiState.isAuthenticated)
                            "电脑端 OpenCode 已通过密钥鉴权，随时可以输入指令。"
                        else
                            "请在电脑端运行 `python desktop_agent/agent.py ${uiState.accountId}` 接入。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.messages) { message ->
                        MessageBubble(message, uiState.appMode)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage, appMode: AppMode) {
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
                                isError -> "系统异常告警"
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
                    Text(
                        text = message.content.ifEmpty { if (message.isStreaming) "▌" else "..." },
                        fontSize = 13.5.sp,
                        fontFamily = if (!isUser) FontFamily.Monospace else FontFamily.Default,
                        lineHeight = 19.sp,
                        color = when {
                            isError -> MaterialTheme.colorScheme.onErrorContainer
                            isUser -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (message.isStreaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appMode == AppMode.CLOUD_HOSTED) "云端工作区正在执行..." else "正在同步电脑端执行...",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
