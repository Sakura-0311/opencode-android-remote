package com.opencode.android.ui.screens

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
import com.opencode.android.ui.components.MessageBubbleWithHighlight
import com.opencode.android.ui.components.MarkdownText
import com.opencode.android.ui.components.looksLikeMarkdown
import com.opencode.android.util.MarkdownExporter
import com.opencode.android.util.ErrorCodes
import com.opencode.android.util.TAG_ALL
import com.opencode.android.util.TAG_DEFAULT
import com.opencode.android.util.TAG_KEY_TO_RES
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun tagDisplay(tag: String): String {
    // v4.3 M-4: 内置标签按 key 映射文案，用户自建标签原样显示
    val res = TAG_KEY_TO_RES[tag]
    return if (res != null) stringResource(res) else tag
}

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
    // v4.3: 语言切换
    onShowLanguage: () -> Unit = {},
    // 对外分发：崩溃上报开关
    crashReportEnabled: Boolean = false,
    onToggleCrashReport: (Boolean) -> Unit = {},
    e2eeEnabled: Boolean = false,
    onToggleE2ee: (Boolean) -> Unit = {},
    // v4.3 M-5: E2EE 状态可见
    e2eePeerReady: Boolean = false,
    showE2eeChannelDialog: Boolean = false,
    onDismissE2eeChannelDialog: () -> Unit = {}
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
                                // v4.3 M-5: E2EE 状态标识（仅 E2EE 开启时显示）
                                if (e2eeEnabled) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val (e2eeLabel, e2eeColor) = if (e2eePeerReady) {
                                        stringResource(R.string.e2ee_001) to Color(0xFF10B981)
                                    } else {
                                        stringResource(R.string.e2ee_002) to Color(0xFFF59E0B)
                                    }
                                    Text(
                                        text = "🔒 $e2eeLabel",
                                        fontSize = 11.sp,
                                        color = e2eeColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            val currentSession = uiState.availableSessions.find { it.id == uiState.currentSessionId }
                            val sessionTag = currentSession?.tag ?: TAG_DEFAULT
                            val sessionTagDisplay = tagDisplay(sessionTag)
                            val statusText = if (uiState.appMode == AppMode.CLOUD_HOSTED) {
                                stringResource(R.string.chat_status_cloud, sessionTagDisplay, currentSession?.title ?: stringResource(R.string.chat_task_cloud))
                            } else {
                                stringResource(R.string.chat_status_desktop, sessionTagDisplay, currentSession?.title ?: stringResource(R.string.chat_task_desktop))
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
                                text = { Text(stringResource(R.string.chat_002)) },
                                onClick = {
                                    showMoreMenu = false
                                    showSessionsModal = true
                                },
                                leadingIcon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_003)) },
                                onClick = {
                                    showMoreMenu = false
                                    val md = onExportMarkdown()
                                    val currentTitle = uiState.availableSessions.find { it.id == uiState.currentSessionId }?.title ?: "OpenCode"
                                    MarkdownExporter.shareMarkdown(context, currentTitle, md)
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_004)) },
                                onClick = {
                                    showMoreMenu = false
                                    val md = onExportMarkdown()
                                    MarkdownExporter.copyToClipboard(context, md)
                                },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_005)) },
                                onClick = {
                                    showMoreMenu = false
                                    onTriggerTestApproval()
                                },
                                leadingIcon = { Icon(Icons.Default.Gavel, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_006)) },
                                onClick = {
                                    showMoreMenu = false
                                    onClearChat()
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
                            )
                            Divider()
                            // B-12: 检查更新
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_007)) },
                                onClick = {
                                    showMoreMenu = false
                                    onCheckUpdate()
                                },
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) }
                            )
                            // v2.5: 连接配置 / 导入导出 / 操作记录
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_008)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowProfiles()
                                },
                                leadingIcon = { Icon(Icons.Default.SwitchAccount, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_009)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowConfigExport()
                                },
                                leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_010)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowConfigImport()
                                },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_011)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowOpLog()
                                },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                            )
                            Divider()
                            // v1.6 P0 多设备管理
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_012)) },
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
                                text = { Text(stringResource(R.string.chat_013)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowProjectCenter()
                                },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                            )
                            // P2-12: 文件浏览器
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_014)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowFileBrowser()
                                },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                            )
                            // P2-13: 任务中心
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_015)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowTaskCenter()
                                },
                                leadingIcon = { Icon(Icons.Default.TaskAlt, contentDescription = null) }
                            )
                            // P2-15: 连接诊断
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_016)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowDiagnose()
                                },
                                leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) }
                            )
                            // 对外分发：隐私说明
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_017)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowPrivacy()
                                },
                                leadingIcon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) }
                            )
                            // v4.3: 语言切换
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.lang_title)) },
                                onClick = {
                                    showMoreMenu = false
                                    onShowLanguage()
                                },
                                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) }
                            )
                            // v4.2: E2EE 端到端加密开关（默认关闭；开启后需重新配对交换密钥）
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.chat_018))
                                        Switch(
                                            checked = e2eeEnabled,
                                            onCheckedChange = { onToggleE2ee(it) }
                                        )
                                    }
                                },
                                onClick = { onToggleE2ee(!e2eeEnabled) },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                            )
                            // 对外分发：崩溃上报开关（默认关闭，重启生效）
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.chat_019))
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
                                text = { Text(stringResource(R.string.chat_020), color = MaterialTheme.colorScheme.error) },
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
                                placeholder = { Text(stringResource(R.string.chat_021), fontSize = 12.sp) },
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
                                    text = stringResource(R.string.chat_022, stringResource(errInfo.titleRes, uiState.appError.code), uiState.appError.message),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.chat_023, stringResource(errInfo.suggestionRes)),
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
                val cloudSuggestions = listOf(stringResource(R.string.chat_024), stringResource(R.string.chat_025), stringResource(R.string.chat_026), stringResource(R.string.chat_027), "/help")
                val desktopSuggestions = listOf(stringResource(R.string.chat_028), stringResource(R.string.chat_027), stringResource(R.string.chat_029), "/help", "/compact")
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val suggestions = if (uiState.appMode == AppMode.CLOUD_HOSTED) cloudSuggestions else desktopSuggestions
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
                            val hint = if (uiState.appMode == AppMode.CLOUD_HOSTED) stringResource(R.string.chat_030) else stringResource(R.string.chat_031)
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
                        text = if (uiState.logSearchQuery.isNotBlank()) stringResource(R.string.chat_032, uiState.logSearchQuery) else stringResource(R.string.chat_033),
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
                        Text(stringResource(R.string.chat_034), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 会话分组与标签管理弹窗
    // v4.3 M-5: 新配对「已建立加密通道」一次提示
    if (showE2eeChannelDialog) {
        AlertDialog(
            onDismissRequest = onDismissE2eeChannelDialog,
            title = { Text("🔒 " + stringResource(R.string.e2ee_003), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.e2ee_004), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = onDismissE2eeChannelDialog) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
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



/**
 * v2.5: 统一连接状态文案。中继模式订阅 relayConnectionState，
 * 云端模式订阅 cloudConnectionState。
 */
@Composable
private fun connectionStatusLabel(s: OpenCodeUiState): String {
    return if (s.appMode == AppMode.CLOUD_HOSTED) {
        stringResource(R.string.chat_044) + when (s.cloudConnectionState) {
            CloudConnectionState.DISCONNECTED -> stringResource(R.string.chat_045)
            CloudConnectionState.CONNECTING -> stringResource(R.string.chat_046)
            CloudConnectionState.STREAMING -> stringResource(R.string.chat_047)
            CloudConnectionState.RECONNECTING -> stringResource(R.string.chat_048)
        }
    } else {
        stringResource(R.string.chat_049) + when (s.relayConnectionState) {
            RelayConnectionState.DISCONNECTED -> stringResource(R.string.chat_045)
            RelayConnectionState.CONNECTING -> stringResource(R.string.chat_046)
            RelayConnectionState.CONNECTED -> stringResource(R.string.chat_050)
            RelayConnectionState.AUTHENTICATING -> stringResource(R.string.chat_051)
            RelayConnectionState.AUTHENTICATED -> stringResource(R.string.chat_052)
            RelayConnectionState.DESKTOP_ONLINE -> stringResource(R.string.chat_053)
            RelayConnectionState.RECONNECTING -> stringResource(R.string.chat_048)
            RelayConnectionState.AUTH_FAILED -> stringResource(R.string.chat_054)
        }
    }
}
