package com.opencode.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.opencode.android.ui.screens.ChatScreen
import com.opencode.android.ui.screens.PairingScreen
import com.opencode.android.ui.theme.OpenCodeTheme
import com.opencode.android.util.UpdateChecker
import com.opencode.android.viewmodel.OpenCodeViewModel
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.ui.screens.DeviceManagementDialog
import com.opencode.android.ui.screens.ModelAgentDialog

class MainActivity : ComponentActivity() {

    private val viewModel: OpenCodeViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // v1.6 P0: 应用已在前台时，点击通知同样跳转到对应会话
        handleNotificationDeepLink(intent)
    }

    /**
     * v1.6 P0 任务通知：通知点击后直接进入对应会话，而不是只打开首页。
     */
    private fun handleNotificationDeepLink(intent: Intent?) {
        val sessionId = intent?.getStringExtra(
            OpenCodeKeepAliveService.EXTRA_OPEN_SESSION
        )?.takeIf { it.isNotBlank() } ?: return
        viewModel.switchSession(sessionId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v1.6 P0 任务通知：处理通知深链（点击通知直达对应会话）
        handleNotificationDeepLink(intent)

        // P1-5: 运行时申请通知权限 (Android 13+ / API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            OpenCodeTheme {
                val uiState by viewModel.uiState.collectAsState()
                // B-12: 更新检查对话框状态
                var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
                var checkingUpdate by remember { mutableStateOf(false) }
                // v1.6 P0 多设备管理
                var showDeviceManager by remember { mutableStateOf(false) }
                // v1.6 P1 Model/Agent
                var showModelAgent by remember { mutableStateOf(false) }

                if (!uiState.isPaired) {
                    PairingScreen(
                        currentMode = uiState.appMode,
                        initialAccountId = uiState.accountId,
                        initialSecret = uiState.secret,
                        initialRelayUrl = uiState.relayUrl,
                        initialCloudUrl = uiState.cloudServerUrl,
                        initialCloudKey = uiState.cloudApiKey,
                        initialCloudWorkspace = uiState.cloudWorkspacePath,
                        appError = uiState.appError,
                        diagnostics = uiState.diagnostics,
                        statusBanner = uiState.statusBanner,
                        onSwitchMode = { mode ->
                            viewModel.switchMode(mode)
                        },
                        onTestConnectivity = {
                            viewModel.testConnectivity()
                        },
                        onConnectDesktop = { accountId, secret, relayUrl ->
                            viewModel.pairDesktop(accountId, secret, relayUrl)
                        },
                        onConnectCloud = { cloudUrl, apiKey, workspace ->
                            viewModel.pairCloud(cloudUrl, apiKey, workspace)
                        },
                        // v1.6 P0 扫码配对
                        onQrPairing = { relayUrl, accountId, pairingToken, desktopName ->
                            viewModel.claimPairingByQr(relayUrl, accountId, pairingToken, desktopName) { _, _ -> }
                        }
                    )
                } else {
                    ChatScreen(
                        uiState = uiState,
                        onSendMessage = { prompt ->
                            viewModel.sendMessage(prompt)
                        },
                        onCancel = {
                            viewModel.cancelExecution()
                        },
                        onClearChat = {
                            viewModel.clearChat()
                        },
                        onDisconnect = {
                            viewModel.unpair()
                        },
                        onDismissError = {
                            viewModel.dismissError()
                        },
                        onSelectTagFilter = { tag ->
                            viewModel.setTagFilter(tag)
                        },
                        onSwitchSession = { id ->
                            viewModel.switchSession(id)
                        },
                        onTogglePinSession = { id ->
                            viewModel.togglePinSession(id)
                        },
                        onArchiveSession = { id ->
                            viewModel.archiveSession(id)
                        },
                        onBatchArchive = {
                            viewModel.batchArchiveOldSessions()
                        },
                        onApproveTool = { callId ->
                            viewModel.approveTool(callId)
                        },
                        onRejectTool = { callId ->
                            viewModel.rejectTool(callId)
                        },
                        onTriggerTestApproval = {
                            viewModel.triggerMockToolApprovalForTest()
                        },
                        onSearchLog = { query ->
                            viewModel.setLogSearchQuery(query)
                        },
                        onSetAutoScrollPaused = { isPaused ->
                            viewModel.setAutoScrollPaused(isPaused)
                        },
                        onExportMarkdown = {
                            viewModel.exportCurrentSession()
                        },
                        // B-12: 检查更新
                        onCheckUpdate = {
                            checkingUpdate = true
                            UpdateChecker.checkForUpdate { info ->
                                checkingUpdate = false
                                updateInfo = info
                            }
                        },
                        // v1.6 P0 多设备管理
                        onRequestDeviceList = {
                            viewModel.requestDeviceList()
                        },
                        onRevokeDevice = { name ->
                            viewModel.revokeDevice(name)
                        },
                        onRenameDevice = { old, new ->
                            viewModel.renameDevice(old, new)
                        },
                        onShowDeviceManager = {
                            showDeviceManager = true
                        },
                        // v1.6 P1 Model/Agent
                        onShowModelAgent = {
                            showModelAgent = true
                        }
                    )
                }

                // B-12: 更新检查结果对话框
                if (checkingUpdate) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("检查更新") },
                        text = { Text("正在检查新版本…") },
                        confirmButton = {}
                    )
                }
                updateInfo?.let { info ->
                    AlertDialog(
                        onDismissRequest = { updateInfo = null },
                        title = { Text(if (info.hasUpdate) "发现新版本" else "检查更新") },
                        text = {
                            Text(
                                when {
                                    info.error != null -> info.error!!
                                    info.hasUpdate -> "当前版本 ${info.currentVersion}\n最新版本 ${info.latestVersion}\n\n${info.releaseNotes ?: ""}\n\n请前往 GitHub Releases 下载 APK 更新。"
                                    else -> "当前已是最新版本 (${info.currentVersion})。"
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { updateInfo = null }) { Text("知道了") }
                        }
                    )
                }

                // v1.6 P0 多设备管理对话框
                if (showDeviceManager) {
                    DeviceManagementDialog(
                        devices = uiState.pairedDevices,
                        onRefresh = { viewModel.requestDeviceList() },
                        onRevoke = { name -> viewModel.revokeDevice(name) },
                        onRename = { old, new -> viewModel.renameDevice(old, new) },
                        onDismiss = { showDeviceManager = false }
                    )
                }

                // v1.6 P1 Model/Agent 选择对话框
                if (showModelAgent) {
                    ModelAgentDialog(
                        agents = uiState.availableAgents,
                        models = uiState.availableModels,
                        selectedAgent = uiState.selectedAgent,
                        selectedModel = uiState.selectedModel,
                        onRefresh = { viewModel.requestModelConfig() },
                        onSelectAgent = { viewModel.selectAgent(it) },
                        onSelectModel = { viewModel.selectModel(it) },
                        onDismiss = { showModelAgent = false }
                    )
                }
            }
        }
    }
}
