package com.opencode.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.opencode.android.util.CrashReporting
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.opencode.android.ui.screens.ChatScreen
import com.opencode.android.ui.screens.PairingScreen
import com.opencode.android.ui.theme.OpenCodeTheme
import com.opencode.android.util.UpdateChecker
import com.opencode.android.viewmodel.OpenCodeViewModel
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.ui.screens.DeviceManagementDialog
import com.opencode.android.ui.screens.DesktopListDialog
import com.opencode.android.ui.screens.FileBrowserDialog
import com.opencode.android.ui.screens.TaskCenterDialog
import com.opencode.android.ui.screens.ConnectionDiagnoseDialog
import com.opencode.android.ui.screens.PrivacyDialog
import com.opencode.android.ui.screens.ModelAgentDialog
import com.opencode.android.ui.screens.ProjectCenterDialog
import com.opencode.android.ui.screens.ProfileManagerDialog
import com.opencode.android.ui.screens.ConfigExportDialog
import com.opencode.android.ui.screens.ConfigImportDialog
import com.opencode.android.ui.screens.OpLogDialog
import com.opencode.android.ui.screens.LanguageDialog

class MainActivity : ComponentActivity() {

    private val viewModel: OpenCodeViewModel by viewModels()

    /**
     * v4.3 多语言：在 Activity 创建前按用户选择的语言包一层 locale，
     * 使 getString()/stringResource() 取到对应语言。跟随系统时不包裹。
     */
    override fun attachBaseContext(newBase: Context) {
        val tag = com.opencode.android.util.LocaleHelper.readSavedLocaleTag(newBase)
        super.attachBaseContext(com.opencode.android.util.LocaleHelper.wrapContext(newBase, tag))
    }

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
                // v2.5: profiles / 配置导入导出 / 操作记录
                var showProfiles by remember { mutableStateOf(false) }
                var showConfigExport by remember { mutableStateOf(false) }
                var showConfigImport by remember { mutableStateOf(false) }
                var showOpLog by remember { mutableStateOf(false) }
                var exportJson by remember { mutableStateOf("") }
                // v1.6 P1 Model/Agent
                var showModelAgent by remember { mutableStateOf(false) }
                // v1.6 P1 项目管理中心
                var showProjectCenter by remember { mutableStateOf(false) }
                // P2-12: 文件浏览器
                var showFileBrowser by remember { mutableStateOf(false) }
                // P2-13: 任务中心
                var showTaskCenter by remember { mutableStateOf(false) }
                // P2-15: 连接诊断
                var showDiagnose by remember { mutableStateOf(false) }
                // 对外分发：隐私说明
                var showPrivacy by remember { mutableStateOf(false) }
                // v4.3: 语言切换对话框
                var showLanguage by remember { mutableStateOf(false) }
                // 对外分发：崩溃上报开关状态
                var crashReportEnabled by remember { mutableStateOf(CrashReporting.isOptedIn(this@MainActivity)) }
                // v4.2: E2EE 运行时开关
                var e2eeEnabled by remember { mutableStateOf(viewModel.prefsManager.isE2eeEnabled) }

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
                            UpdateChecker.checkForUpdate(this@MainActivity) { info ->
                                checkingUpdate = false
                                updateInfo = info
                            }
                        },
                        // v1.6 P0 多设备管理
                        onRequestDeviceList = {
                            viewModel.requestDeviceList()
                        },
                        onRevokeDevice = { id, name ->
                            viewModel.revokeDevice(id, name)
                        },
                        onRenameDevice = { id, old, new ->
                            viewModel.renameDevice(id, old, new)
                        },
                        onShowDeviceManager = {
                            showDeviceManager = true
                        },
                        // v2.5
                        onShowProfiles = { showProfiles = true },
                        onShowConfigExport = { exportJson = viewModel.exportConfigJson(); showConfigExport = true },
                        onShowConfigImport = { showConfigImport = true },
                        onShowOpLog = { showOpLog = true },
                        // v1.6 P1 Model/Agent
                        onShowModelAgent = {
                            showModelAgent = true
                        },
                        // v1.6 P1 项目管理中心
                        onShowProjectCenter = {
                            showProjectCenter = true
                        },
                        // P2-12: 文件浏览器
                        onShowFileBrowser = {
                            showFileBrowser = true
                            viewModel.openFileBrowser()
                        },
                        // P2-13: 任务中心
                        onShowTaskCenter = {
                            showTaskCenter = true
                        },
                        // P2-15: 连接诊断
                        onShowDiagnose = {
                            showDiagnose = true
                        },
                        // 对外分发：隐私说明
                        onShowPrivacy = {
                            showPrivacy = true
                        },
                        // v4.3: 语言切换
                        onShowLanguage = {
                            showLanguage = true
                        },
                        // 对外分发：崩溃上报开关
                        crashReportEnabled = crashReportEnabled,
                        e2eeEnabled = e2eeEnabled,
                        e2eePeerReady = uiState.e2eePeerReady,
                        showE2eeChannelDialog = uiState.showE2eeChannelDialog,
                        onDismissE2eeChannelDialog = { viewModel.dismissE2eeChannelDialog() },
                        onToggleE2ee = { enabled ->
                            viewModel.setE2eeEnabled(enabled)
                            e2eeEnabled = enabled
                            Toast.makeText(
                                this@MainActivity,
                                if (enabled) getString(R.string.main_001) else getString(R.string.main_002),
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onToggleCrashReport = { enabled ->
                            CrashReporting.setOptedIn(this@MainActivity, enabled)
                            crashReportEnabled = enabled
                            Toast.makeText(
                                this@MainActivity,
                                if (enabled) getString(R.string.main_003) else getString(R.string.main_004),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                // B-12: 更新检查结果对话框
                if (checkingUpdate) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.main_005)) },
                        text = { Text(stringResource(R.string.main_006)) },
                        confirmButton = {}
                    )
                }
                updateInfo?.let { info ->
                    AlertDialog(
                        onDismissRequest = { updateInfo = null },
                        title = { Text(if (info.hasUpdate) stringResource(R.string.main_007) else stringResource(R.string.main_005)) },
                        text = {
                            Text(
                                when {
                                    info.error != null -> info.error!!
                                    info.hasUpdate -> stringResource(R.string.main_008, info.currentVersion, info.latestVersion, info.releaseNotes ?: "")
                                    else -> stringResource(R.string.main_009, info.currentVersion)
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { updateInfo = null }) { Text(stringResource(R.string.main_010)) }
                        }
                    )
                }

                // v2.5: 连接配置管理
                if (showProfiles) {
                    ProfileManagerDialog(
                        profiles = uiState.profiles,
                        activeProfileId = uiState.activeProfileId,
                        onSwitch = { viewModel.switchProfile(it) },
                        onAdd = { viewModel.addProfile(it) },
                        onRename = { id, name -> viewModel.renameProfile(id, name) },
                        onDelete = { viewModel.deleteProfile(it) },
                        onDismiss = { showProfiles = false }
                    )
                }
                if (showConfigExport) {
                    ConfigExportDialog(json = exportJson, onDismiss = { showConfigExport = false })
                }
                if (showConfigImport) {
                    ConfigImportDialog(
                        onImport = { viewModel.importConfigJson(it) },
                        onDismiss = { showConfigImport = false }
                    )
                }
                if (showOpLog) {
                    OpLogDialog(onDismiss = { showOpLog = false })
                }

                // v1.6 P0 多设备管理对话框
                if (showDeviceManager) {
                    DeviceManagementDialog(
                        devices = uiState.pairedDevices,
                        onRefresh = { viewModel.requestDeviceList() },
                        onRevoke = { id, name -> viewModel.revokeDevice(id, name) },
                        onRename = { id, old, new -> viewModel.renameDevice(id, old, new) },
                        onDismiss = { showDeviceManager = false },
                        onOpenDesktopList = { viewModel.openDesktopList() }
                    )
                }

                // v3.1: 在线 desktop 列表（选择目标电脑）
                if (uiState.showDesktopList) {
                    DesktopListDialog(
                        desktops = uiState.desktopList,
                        selectedDeviceId = uiState.targetDesktopId,
                        onRefresh = { viewModel.refreshDesktopList() },
                        onSelect = { viewModel.requestTargetSwitch(it) },
                        onDismiss = { viewModel.closeDesktopList() }
                    )
                }

                // v3.2: 安全存储迁移回退 → 一次性用户提示
                if (uiState.showSecureMigrationNotice) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissSecureMigrationNotice() },
                        title = { Text(stringResource(R.string.main_011)) },
                        text = { Text(stringResource(R.string.main_012)) },
                        confirmButton = {
                            Button(onClick = { viewModel.dismissSecureMigrationNotice() }) { Text(stringResource(R.string.main_010)) }
                        }
                    )
                }

                // v4.0: v3 旧服务端升级提示（功能可用，不阻断）
                if (uiState.showOldRelayWarning) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissOldRelayWarning() },
                        title = { Text(stringResource(R.string.main_013)) },
                        text = { Text(stringResource(R.string.main_014)) },
                        confirmButton = {
                            Button(onClick = { viewModel.dismissOldRelayWarning() }) { Text(stringResource(R.string.main_010)) }
                        }
                    )
                }

                // v3.1: 切换目标电脑时有进行中会话 → 确认
                uiState.pendingTargetSwitch?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { viewModel.cancelTargetSwitch() },
                        title = { Text(stringResource(R.string.main_015)) },
                        text = { Text(stringResource(R.string.main_016)) },
                        confirmButton = {
                            Button(onClick = { viewModel.confirmTargetSwitch() }) { Text(stringResource(R.string.main_017)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.cancelTargetSwitch() }) { Text(stringResource(R.string.main_018)) }
                        }
                    )
                }

                // v1.6 P1 Model/Agent 选择对话框
                if (showModelAgent) {
                    ModelAgentDialog(
                        agents = uiState.availableAgents,
                        models = uiState.availableModels,
                        selectedAgent = uiState.selectedAgent,
                        selectedModel = uiState.selectedModel,
                        configError = uiState.configError,
                        onRefresh = { viewModel.requestModelConfig() },
                        onSelectAgent = { viewModel.selectAgent(it) },
                        onSelectModel = { viewModel.selectModel(it) },
                        onDismiss = { showModelAgent = false }
                    )
                }

                // v1.6 P1 项目管理中心
                if (showProjectCenter) {
                    ProjectCenterDialog(
                        projects = uiState.projects,
                        favoriteIds = uiState.favoriteProjectIds,
                        deviceName = uiState.accountId,
                        projectsError = uiState.projectsError,
                        onRefresh = { viewModel.requestProjects() },
                        onToggleFavorite = { viewModel.toggleFavoriteProject(it) },
                        onOpenProject = {
                            // 进入项目：关闭对话框（会话列表已按项目过滤，后续版本增强）
                            showProjectCenter = false
                        },
                        onDismiss = { showProjectCenter = false }
                    )
                }

                // P2-12: 文件浏览器
                if (showFileBrowser) {
                    FileBrowserDialog(
                        currentPath = uiState.fileBrowserPath,
                        entries = uiState.fileBrowserEntries,
                        loading = uiState.fileBrowserLoading,
                        previewPath = uiState.filePreviewPath,
                        previewContent = uiState.filePreviewContent,
                        previewTruncated = uiState.filePreviewTruncated,
                        onNavigate = { viewModel.navigateFileBrowser(it) },
                        onPreview = { viewModel.previewFile(it) },
                        onClosePreview = { viewModel.closeFilePreview() },
                        onRefresh = { viewModel.openFileBrowser(uiState.fileBrowserPath) },
                        onDismiss = { showFileBrowser = false }
                    )
                }

                // P2-13: 任务中心
                if (showTaskCenter) {
                    TaskCenterDialog(
                        uiState = uiState,
                        onSelectSession = { viewModel.switchSession(it) },
                        onCancelTask = { viewModel.cancelExecution() },
                        onApprove = {
                            uiState.pendingApproval?.callId?.let { viewModel.approveTool(it) }
                        },
                        onReject = {
                            uiState.pendingApproval?.callId?.let { viewModel.rejectTool(it) }
                        },
                        onDismiss = { showTaskCenter = false }
                    )
                }

                // P2-15: 连接诊断
                if (showDiagnose) {
                    ConnectionDiagnoseDialog(
                        uiState = uiState,
                        onRunDiagnose = { viewModel.runDiagnose() },
                        onDismiss = { showDiagnose = false }
                    )
                }

                // 对外分发：隐私说明
                if (showPrivacy) {
                    PrivacyDialog(onDismiss = { showPrivacy = false })
                }

                // v4.3: 语言切换（选择后保存偏好并 recreate 即时生效；API 33+ 同步系统应用语言设置）
                if (showLanguage) {
                    LanguageDialog(
                        currentTag = viewModel.prefsManager.appLocale,
                        onSelect = { tag ->
                            viewModel.prefsManager.appLocale = tag
                            com.opencode.android.util.LocaleHelper.syncToSystem(this@MainActivity, tag)
                            showLanguage = false
                            recreate()
                        },
                        onDismiss = { showLanguage = false }
                    )
                }
            }
        }
    }
}
