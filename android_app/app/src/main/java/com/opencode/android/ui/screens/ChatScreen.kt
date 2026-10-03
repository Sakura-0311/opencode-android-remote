package com.opencode.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: OpenCodeUiState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    onClearChat: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
    // 会话与分组管理
    onSelectTagFilter: (String?) -> Unit,
    onSwitchSession: (String) -> Unit,
    onTogglePinSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit,
    onBatchArchive: () -> Unit,
    // 工具审批
    onApproveTool: (String) -> Unit,
    onRejectTool: (String) -> Unit,
    onTriggerTestApproval: () -> Unit,
    // 搜索与滚动
    onSearchLog: (String) -> Unit,
    onSetAutoScrollPaused: (Boolean) -> Unit,
    onExportMarkdown: () -> String,
    // B-12: 检查更新
    onCheckUpdate: () -> Unit,
    // v1.6 P0 多设备管理
    onRequestDeviceList: () -> Unit = {},
    onRevokeDevice: (String, String) -> Unit = { _, _ -> },
    onRenameDevice: (String, String, String) -> Unit = { _, _, _ -> },
    onShowDeviceManager: () -> Unit = {},
    // v1.6 P1 Model/Agent
    onShowModelAgent: () -> Unit = {},
    // v1.6 P1 项目管理中心
    onShowProjectCenter: () -> Unit = {},
    // P2-12: 文件浏览器
    onShowFileBrowser: () -> Unit = {},
    // P2-13: 任务中心
    onShowTaskCenter: () -> Unit = {},
    // P2-15: 连接诊断
    onShowDiagnose: () -> Unit = {},
    // v2.5: profiles / 配置导入导出 / 操作记录
    onShowProfiles: () -> Unit = {},
    onShowConfigExport: () -> Unit = {},
    onShowConfigImport: () -> Unit = {},
    onShowOpLog: () -> Unit = {},
    // 对外分发：隐私说明
    onShowPrivacy: () -> Unit = {},
    // 对外分发：崩溃上报开关
    crashReportEnabled: Boolean = false,
    onToggleCrashReport: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var showSessionsModal by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // 触摸/手动滑动检测：当用户向上滑动浏览历史时，暂停自动滚底
    val isUserScrolling = listState.isScrollInProgress
    LaunchedEffect(isUserScrolling) {
        if (isUserScrolling && listState.canScrollForward) {
            onSetAutoScrollPaused(true)
        }
    }

    // 新消息或流式输出自动滚底（在未暂停时）
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content?.length) {
        if (!uiState.isAutoScrollPaused && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // 渲染工具审批弹窗 (精简 Diff 预览)
    if (uiState.pendingApproval != null) {
        ToolApprovalDialog(
            request = uiState.pendingApproval,
            onApprove = onApproveTool,
            onReject = onRejectTool
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showSessionsModal = true }
                            ) {
                                Text(
                                    text = if (uiState.appMode == AppMode.CLOUD_HOSTED) "OpenCode Cloud" else "OpenCode Remote",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Switch Session",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // 状态圆点
                                val dotColor = when {
                                    uiState.appMode == AppMode.CLOUD_HOSTED && uiState.isAuthenticated -> Color(0xFF10B981)
                                    uiState.isDesktopOnline && uiState.isAuthenticated -> Color(0xFF10B981)
                                    uiState.isRelayConnected -> Color(0xFFF59E0B)
                                    else -> Color(0xFFEF4444)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                // v2.5: 统一连接状态条（按 appMode 二选一订阅）
                                Text(
                                    text = connectionStatusLabel(uiState),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                            val currentSession = uiState.availableSessions.find { it.id == uiState.currentSessionId }
                            val sessionTag = currentSession?.tag ?: "默认"
                            val statusText = if (uiState.appMode == AppMode.CLOUD_HOSTED) {
                                "☁️ [${sessionTag}] ${currentSession?.title ?: "云端任务"}"
                            } else {
                                "💻 [${sessionTag}] ${currentSession?.title ?: "本地任务"}"
                            }
                            Text(
                                text = statusText,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(Icons.Default.Search, contentDescription = "Search Log")
                        }
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("管理会话与标签") },
                                onClick = {
                                    showMoreMenu = false
                                    showSessionsModal = true
                                },
                                leadingIcon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("导出为 Markdown") },
                                onClick = {
                                    showMoreMenu = false
                                    val md = onExportMarkdown()
                                    val currentTitle = uiState.availableSessions.find { it.id == uiState.currentSessionId }?.title ?: "OpenCode"
                                    MarkdownExporter.shareMarkdown(context, currentTitle, md)
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("复制完整 Markdown") },
                                onClick = {
                                    showMoreMenu = false
                                    val md = onExportMarkdown()
                                    MarkdownExporter.copyToClipboard(context, md)
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("模拟 Diff 审批弹窗 (测试)") },
                                onClick = {
                                    showMoreMenu = false
                                    onTriggerTestApproval()
                                },
                                leadingIcon = { Icon(Icons.Default.Gavel, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空当前聊天记录") },
                                onClick = {
                                    showMoreMenu = false
                                    onClearChat()
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
                            )
                            Divider()
                            // B-12: 检查更新
                            DropdownMenuItem(
                                text = { Text("检查更新") },
                                onClick = {
                                    showMoreMenu = false
                                    onCheckUpdate()
                                },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) }
                            )
                            // v2.5: 连接配置 / 导入导出 / 操作记录
                            DropdownMenuItem(
                                text = { Text("连接配置") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowProfiles()
                                },
                                leadingIcon = { Icon(Icons.Default.SwitchAccount, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("导出配置") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowConfigExport()
                                },
                                leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("导入配置") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowConfigImport()
                                },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("操作记录") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowOpLog()
                                },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                            )
                            Divider()
                            // v1.6 P0 多设备管理
                            DropdownMenuItem(
                                text = { Text("设备管理") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowDeviceManager()
                                },
                                leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null) }
                            )
                            // v1.6 P1 Model/Agent
                            DropdownMenuItem(
                                text = { Text("Model / Agent") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowModelAgent()
                                },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                            )
                            // v1.6 P1 项目管理中心
                            DropdownMenuItem(
                                text = { Text("项目中心") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowProjectCenter()
                                },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                            )
                            // P2-12: 文件浏览器
                            DropdownMenuItem(
                                text = { Text("文件浏览器") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowFileBrowser()
                                },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                            )
                            // P2-13: 任务中心
                            DropdownMenuItem(
                                text = { Text("任务中心") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowTaskCenter()
                                },
                                leadingIcon = { Icon(Icons.Default.TaskAlt, contentDescription = null) }
                            )
                            // P2-15: 连接诊断
                            DropdownMenuItem(
                                text = { Text("连接诊断") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowDiagnose()
                                },
                                leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) }
                            )
                            // 对外分发：隐私说明
                            DropdownMenuItem(
                                text = { Text("隐私说明") },
                                onClick = {
                                    showMoreMenu = false
                                    onShowPrivacy()
                                },
                                leadingIcon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) }
                            )
                            // 对外分发：崩溃上报开关（默认关闭，重启生效）
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("崩溃上报")
                                        Switch(
                                            checked = crashReportEnabled,
                                            onCheckedChange = { onToggleCrashReport(it) }
                                        )
                                    }
                                },
                                onClick = { onToggleCrashReport(!crashReportEnabled) },
                                leadingIcon = { Icon(Icons.Default.BugReport, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("断开连接", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMoreMenu = false
                                    onDisconnect()
                                },
                                leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )

                // 搜索栏展开视图
                AnimatedVisibility(visible = isSearchActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = uiState.logSearchQuery,
                                onValueChange = onSearchLog,
                                placeholder = { Text("在日志与消息中检索关键词...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(12.dp)
                            )
                            if (uiState.logSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchLog("") }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // 错误提示条
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
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            // v2.5: 错误码映屄表（标题+建议）
                            val errInfo = remember(uiState.appError.code) { ErrorCodes.lookup(uiState.appError.code) }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${errInfo.title}：${uiState.appError.message}",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "建议：${errInfo.suggestion}",
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // 状态通知条
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

                // 输入框与发送按钮
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
                            val hint = if (uiState.appMode == AppMode.CLOUD_HOSTED) "给云端 OpenCode 下达指令..." else "给电脑端 OpenCode 下达指令..."
                            Text(hint)
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
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
            val filteredMessages = if (uiState.logSearchQuery.isBlank()) {
                uiState.messages
            } else {
                uiState.messages.filter { it.content.contains(uiState.logSearchQuery, ignoreCase = true) }
            }

            if (filteredMessages.isEmpty()) {
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
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (uiState.logSearchQuery.isNotBlank()) "未找到匹配 \"${uiState.logSearchQuery}\" 的记录" else "暂无聊天记录",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
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
                    items(filteredMessages) { message ->
                        MessageBubbleWithHighlight(message, uiState.appMode, uiState.logSearchQuery)
                    }
                }
            }

            // 触摸暂停滚动提示浮标
            if (uiState.isAutoScrollPaused) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clickable {
                            onSetAutoScrollPaused(false)
                            coroutineScope.launch {
                                if (uiState.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(uiState.messages.size - 1)
                                }
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("自动滚动已暂停 · 点击回到底部", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 会话分组与标签管理弹窗
    if (showSessionsModal) {
        SessionsManagementModal(
            sessions = uiState.availableSessions,
            currentSessionId = uiState.currentSessionId,
            selectedTagFilter = uiState.selectedTagFilter,
            availableTags = uiState.availableTags,
            onDismiss = { showSessionsModal = false },
            onSelectSession = {
                onSwitchSession(it)
                showSessionsModal = false
            },
            onSelectTagFilter = onSelectTagFilter,
            onTogglePin = onTogglePinSession,
            onArchive = onArchiveSession,
            onBatchArchive = onBatchArchive
        )
    }
}

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
                    val rawText = message.content.ifEmpty { if (message.isStreaming) "▌" else "..." }
                    val useMarkdown = !isUser && !isError && highlightQuery.isBlank() &&
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
                        text = if (appMode == AppMode.CLOUD_HOSTED) "云端工作区正在执行..." else "正在同步电脑端执行...",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun SessionsManagementModal(
    sessions: List<SessionItem>,
    currentSessionId: String,
    selectedTagFilter: String?,
    availableTags: List<String>,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSelectTagFilter: (String?) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String) -> Unit,
    onBatchArchive: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("会话分组与标签管理", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                // 标签快速筛选栏
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableTags) { tag ->
                        val isSelected = (selectedTagFilter == null && tag == "全部") || (selectedTagFilter == tag)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectTagFilter(if (tag == "全部") null else tag) },
                            label = { Text(tag, fontSize = 11.sp) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                val filteredList = sessions.filter { s ->
                    val matchTag = selectedTagFilter == null || s.tag == selectedTagFilter
                    matchTag && !s.isArchived
                }

                if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("当前分类下无活跃会话", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredList) { session ->
                            val isCurrent = session.id == currentSessionId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectSession(session.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { onTogglePin(session.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (session.isPinned) Icons.Default.PushPin else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = "Pin",
                                            tint = if (session.isPinned) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(session.title, fontSize = 13.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                        Text("标签: ${session.tag}", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(
                                        onClick = { onArchive(session.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = "Archive", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onBatchArchive,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("批量归档旧会话 (保留置顶)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

/**
 * v2.5: 统一连接状态文案。中继模式订阅 relayConnectionState，
 * 云端模式订阅 cloudConnectionState。
 */
private fun connectionStatusLabel(s: OpenCodeUiState): String {
    return if (s.appMode == AppMode.CLOUD_HOSTED) {
        "云端·" + when (s.cloudConnectionState) {
            CloudConnectionState.DISCONNECTED -> "未连接"
            CloudConnectionState.CONNECTING -> "连接中"
            CloudConnectionState.STREAMING -> "会话进行中"
            CloudConnectionState.RECONNECTING -> "重连中"
        }
    } else {
        "中继·" + when (s.relayConnectionState) {
            RelayConnectionState.DISCONNECTED -> "未连接"
            RelayConnectionState.CONNECTING -> "连接中"
            RelayConnectionState.CONNECTED -> "已连接，待鉴权"
            RelayConnectionState.AUTHENTICATING -> "鉴权中"
            RelayConnectionState.AUTHENTICATED -> "已鉴权，等 Desktop"
            RelayConnectionState.DESKTOP_ONLINE -> "Desktop 在线"
            RelayConnectionState.RECONNECTING -> "重连中"
            RelayConnectionState.AUTH_FAILED -> "鉴权失败"
        }
    }
}
}
