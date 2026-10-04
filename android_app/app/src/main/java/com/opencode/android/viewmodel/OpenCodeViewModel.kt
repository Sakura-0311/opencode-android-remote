package com.opencode.android.viewmodel

import com.opencode.android.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.OpenCodeApp
import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.DiagnosticsResult
import com.opencode.android.data.model.DiffLine
import com.opencode.android.data.model.DiffLineType
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem
import com.opencode.android.data.model.TaskStatus
import com.opencode.android.data.model.ToolApprovalRequest
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.CloudStreamListener
import com.opencode.android.network.CloudConnectionState
import com.opencode.android.util.OpLog
import com.opencode.android.util.TAG_ALL
import com.opencode.android.util.ConfigImportExport
import com.opencode.android.data.model.ConnectionProfile
import com.opencode.android.network.DeviceInfo
import com.opencode.android.network.DesktopInfo
import com.opencode.android.network.FileEntry
import com.opencode.android.network.AgentInfo
import com.opencode.android.network.ModelInfo
import com.opencode.android.network.ProjectInfo
import com.opencode.android.network.PairClaimResult
import com.opencode.android.network.PairingClient
import com.opencode.android.network.CloudTransport
import com.opencode.android.network.RelayListener
import com.opencode.android.network.RelayTransport
import com.opencode.android.network.Transport
import com.opencode.android.network.TransportFactory
import com.opencode.android.network.TransportListener
import com.opencode.android.network.TransportParams
import com.opencode.android.util.AppLog
import com.opencode.android.util.DesktopRoutingPolicy
import com.opencode.android.util.FeatureFlags
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.network.TunnelDiagnosticsHelper
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.util.MarkdownExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.util.UUID

class OpenCodeViewModel(application: Application) : AndroidViewModel(application), RelayListener, CloudStreamListener {

    val prefsManager = PreferencesManager(application.applicationContext)
    // v1.6 P0 后台保活：连接由 Application 持有，与 ViewModel 生命周期解耦
    private val app = application as OpenCodeApp
    private val relayClient: RelayWebSocketClient = app.relayClient
    // v4.1: E2EE（与注入 client 的为同一实例语义；此处用于配对公钥交换）
    private val e2eeManager = com.opencode.android.security.E2eeManager(prefsManager)
    private val cloudClient: CloudApiClient = app.cloudClient

    // v3.0: 传输抽象（ViewModel 仍是 RelayListener/CloudStreamListener，业务回调不变）
    private val transportListener = object : TransportListener {
        override fun onTransportStateChanged(connected: Boolean, detail: String) {
            AppLog.i("Transport", "state: connected=$connected detail=$detail")
        }

        override fun onTransportError(code: String, message: String) {
            _uiState.update { it.copy(appError = AppError(code, message)) }
        }
    }
    private val relayTransport: Transport by lazy { RelayTransport(relayClient, this) }
    private val cloudTransport: Transport by lazy { CloudTransport(cloudClient, this) }

    /** v3.0: 按当前 appMode 选择传输（transport 切换的唯一决策点） */
    fun activeTransport(): Transport =
        TransportFactory.select(_uiState.value.appMode, relayTransport, cloudTransport)

    /** v3.0: 关闭当前传输 */
    fun disconnectTransport() = activeTransport().close()

    private val _uiState: MutableStateFlow<OpenCodeUiState>

    /**
     * v1.6 P0 后台保活：统一任务状态流转（同步 UI + 持久化 + 通知栏）。
     */
    private fun setTaskStatus(status: TaskStatus, detail: String = "", sessionId: String = "") {
        val sid = sessionId.ifBlank { _uiState.value.currentSessionId }
        prefsManager.saveTaskStatus(status.name, detail, sid)
        _uiState.update {
            it.copy(
                taskStatus = status,
                taskStatusDetail = detail,
                // P2-13: 任务中心用——任务开始时间（用于计算耗时）
                taskStartTimeMs = if (status == TaskStatus.RUNNING) System.currentTimeMillis() else it.taskStartTimeMs,
                isGenerating = status == TaskStatus.RUNNING
                        || status == TaskStatus.WAITING_INPUT
                        || status == TaskStatus.APPROVAL_REQUIRED
            )
        }
    }

    init {
        // v1.6: 序号持久化已在 OpenCodeApp.onCreate 中初始化
        // P1-1: 彻底移除虚假写死的 Demo 会话数据，以真实服务拉取为准
        val savedSessions = prefsManager.getSavedSessions()

        // v1.6 P0 后台保活：恢复任务状态（App 重启后）
        val (savedStatus, savedDetail, savedTaskSession) = prefsManager.getTaskStatus()
        val restoredStatus = try {
            TaskStatus.valueOf(savedStatus)
        } catch (e: Exception) { TaskStatus.IDLE }
        // 若上次退出时任务还在进行中，标记为已断开（需重连同步），而非假装仍在运行
        val effectiveStatus = if (restoredStatus == TaskStatus.RUNNING
            || restoredStatus == TaskStatus.WAITING_INPUT
            || restoredStatus == TaskStatus.APPROVAL_REQUIRED) {
            TaskStatus.DISCONNECTED
        } else restoredStatus
        val effectiveDetail = if (effectiveStatus == TaskStatus.DISCONNECTED && restoredStatus != TaskStatus.IDLE) {
            getApplication<Application>().getString(R.string.vm_001, savedDetail)
        } else savedDetail

        _uiState = MutableStateFlow(
            OpenCodeUiState(
                appMode = prefsManager.getAppMode(),
                accountId = prefsManager.getAccountId(),
                secret = prefsManager.getSecret(),
                relayUrl = prefsManager.getRelayUrl(),
                cloudServerUrl = prefsManager.getCloudServerUrl(),
                cloudApiKey = prefsManager.getCloudApiKey(),
                cloudWorkspacePath = prefsManager.getCloudWorkspacePath(),
                profiles = prefsManager.getProfiles(),
                activeProfileId = prefsManager.getActiveProfileId(),
                availableSessions = savedSessions,
                currentSessionId = savedSessions.firstOrNull()?.id ?: "",
                taskStatus = effectiveStatus,
                taskStatusDetail = effectiveDetail,
                // v3.1: 恢复已选目标电脑（按 profile 隔离）
                targetDesktopId = prefsManager.getTargetDesktopId(),
                // v3.2: 安全存储状态（诊断页展示；迁移回退时用户可见）
                secureStorageInfo = prefsManager.secureStorageInfo,
                secureStorageOk = !prefsManager.secureMigrationRolledBack && prefsManager.isSecureStorageAvailable,
                showSecureMigrationNotice = prefsManager.secureMigrationRolledBack &&
                    !prefsManager.wasSecureMigrationNoticeDismissed()
            )
        )

        // v3.0.1 热修：setListener 会同步回调 onConnectionStateChanged（v2.1 引入的
        // WS 状态机），必须在 _uiState 就绪后调用，否则 NPE 闪退（v2.1~v3.0 全版本）。
        // v1.6 P0 后台保活：ViewModel 重建时重新挂载到应用级连接（不断连）
        // CloudApiClient 每次调用时传入 listener，无需重新挂载
        relayClient.setListener(this)
    }

    val uiState: StateFlow<OpenCodeUiState> = _uiState.asStateFlow()

    private var activeAssistantMessageId: String? = null
    // v1.6 P0 任务通知：任务计时与文件统计
    private var taskStartTimeMs: Long = 0L
    private var taskName: String = ""
    private val taskModifiedFiles = mutableSetOf<String>()
    // P1-5: 流式 chunk 批处理缓冲（50ms 聚合一次刷新 UI）
    private val streamBuffer = StringBuilder()
    private var streamFlushJob: Job? = null
    private var streamFlushSessionId: String? = null
    private var lastProgressNotifyMs: Long = 0L

    companion object {
        private const val MAX_MESSAGES_COUNT = 500
        private const val MAX_STREAM_LINES = 2000
        // P1-5: chunk 批处理间隔（文档建议 30–80ms）
        private const val STREAM_FLUSH_MS = 50L
    }

    fun switchMode(mode: AppMode) {
        prefsManager.saveAppMode(mode)
        _uiState.update { it.copy(appMode = mode, appError = null, diagnostics = null) }
    }

    // =========================================================================
    // 1. 会话分组、标签管理、置顶与归档 (基于真实 Session 数据)
    // =========================================================================

    fun setTagFilter(tag: String?) {
        // v4.3 M-4: 按稳定 key 过滤
        val finalTag = if (tag == TAG_ALL) null else tag
        _uiState.update { it.copy(selectedTagFilter = finalTag) }
    }

    fun togglePinSession(sessionId: String) {
        _uiState.update { state ->
            val updated = state.availableSessions.map { s ->
                if (s.id == sessionId) s.copy(isPinned = !s.isPinned, updatedAt = System.currentTimeMillis()) else s
            }
            prefsManager.saveSessions(updated)
            state.copy(availableSessions = sortSessions(updated))
        }
    }

    fun archiveSession(sessionId: String) {
        _uiState.update { state ->
            val updated = state.availableSessions.map { s ->
                if (s.id == sessionId) s.copy(isArchived = true, updatedAt = System.currentTimeMillis()) else s
            }
            prefsManager.saveSessions(updated)
            state.copy(availableSessions = sortSessions(updated))
        }
    }

    fun batchArchiveOldSessions() {
        _uiState.update { state ->
            val updated = state.availableSessions.map { s ->
                if (!s.isPinned && s.id != state.currentSessionId) s.copy(isArchived = true) else s
            }
            prefsManager.saveSessions(updated)
            state.copy(availableSessions = sortSessions(updated))
        }
    }

    fun setSessionTag(sessionId: String, newTag: String) {
        _uiState.update { state ->
            val updated = state.availableSessions.map { s ->
                if (s.id == sessionId) s.copy(tag = newTag, updatedAt = System.currentTimeMillis()) else s
            }
            val tags = (state.availableTags + newTag).distinct()
            prefsManager.saveSessions(updated)
            state.copy(availableSessions = sortSessions(updated), availableTags = tags)
        }
    }

    fun switchSession(sessionId: String) {
        // v3.4: 空值防御——空 sessionId 忽略，不清空当前会话
        if (sessionId.isBlank()) return
        _uiState.update { it.copy(currentSessionId = sessionId, messages = emptyList()) }
    }

    private fun sortSessions(sessions: List<SessionItem>): List<SessionItem> {
        return sessions.sortedWith(
            compareByDescending<SessionItem> { it.isPinned }
                .thenBy { it.isArchived }
                .thenByDescending { it.updatedAt }
        )
    }

    // =========================================================================
    // 2. 工具审批与代码 Diff 预览 (P0-3: 彻底接通真实协议，废除虚假 /approve 文本)
    // =========================================================================

    fun triggerMockToolApprovalForTest() {
        val sampleDiff = listOf(
            DiffLine(DiffLineType.HEADER, "@@ -12,6 +12,8 @@ class AuthService"),
            DiffLine(DiffLineType.UNCHANGED, "    fun validateToken(token: String): Boolean {"),
            DiffLine(DiffLineType.UNCHANGED, "        if (token.isEmpty()) return false"),
            DiffLine(DiffLineType.REMOVED, "        // return legacyTokenCheck(token)"),
            DiffLine(DiffLineType.ADDED, "        val claims = jwtVerifier.verify(token)"),
            DiffLine(DiffLineType.ADDED, "        return claims.isValid && !claims.isExpired"),
            DiffLine(DiffLineType.UNCHANGED, "    }")
        )
        val approval = ToolApprovalRequest(
            callId = UUID.randomUUID().toString(),
            toolName = "edit_file",
            filePath = "src/auth/AuthService.kt",
            summary = getApplication<Application>().getString(R.string.vm_003),
            diffLines = sampleDiff
        )
        _uiState.update { it.copy(pendingApproval = approval) }
        OpenCodeKeepAliveService.notifyApprovalRequired(getApplication(), "edit_file: AuthService.kt")
    }

    fun approveTool(callId: String) {
        val state = _uiState.value
        val nonce = state.pendingApproval?.nonce
        _uiState.update { it.copy(pendingApproval = null) }
        OpLog.record(getApplication(), OpLog.OpType.APPROVE, "callId=$callId")
        // P0-3 修复：直接回传真实权限审批决定，绝不再把 "/approve" 作为普通 prompt 发给大模型！
        // B-5: nonce 原样回传；B-9: 云端模式直调 OpenCode 权限端点
        if (state.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendApprovalResponse(callId, true, nonce = nonce)
        } else if (state.appMode == AppMode.CLOUD_HOSTED) {
            cloudClient.respondToPermission(
                state.cloudServerUrl, state.cloudApiKey,
                state.currentSessionId, callId, true, ""
            )
        }
    }

    fun rejectTool(callId: String) {
        val state = _uiState.value
        val nonce = state.pendingApproval?.nonce
        _uiState.update { it.copy(pendingApproval = null) }
        OpLog.record(getApplication(), OpLog.OpType.REJECT, "callId=$callId")
        // P0-3 修复：回传真实拒绝决定；B-9: 云端模式同上
        if (state.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendApprovalResponse(callId, false, getApplication<Application>().getString(R.string.vm_004), nonce)
        } else if (state.appMode == AppMode.CLOUD_HOSTED) {
            cloudClient.respondToPermission(
                state.cloudServerUrl, state.cloudApiKey,
                state.currentSessionId, callId, false, getApplication<Application>().getString(R.string.vm_004)
            )
        }
    }

    // =========================================================================
    // 3. 连通性测试与隧道适配排查
    // =========================================================================

    fun testConnectivity() {
        val targetUrl = if (_uiState.value.appMode == AppMode.CLOUD_HOSTED) {
            _uiState.value.cloudServerUrl
        } else {
            _uiState.value.relayUrl
        }
        val key = if (_uiState.value.appMode == AppMode.CLOUD_HOSTED) _uiState.value.cloudApiKey else _uiState.value.secret

        _uiState.update {
            it.copy(
                diagnostics = DiagnosticsResult(isChecking = true, statusTitle = getApplication<Application>().getString(R.string.vm_005))
            )
        }

        TunnelDiagnosticsHelper.diagnoseEndpoint(getApplication(), targetUrl, key) { result ->
            _uiState.update { it.copy(diagnostics = result) }
        }
    }

    fun clearDiagnostics() {
        _uiState.update { it.copy(diagnostics = null) }
    }

    // =========================================================================
    // 4. 日志搜索与触摸暂停滚动
    // =========================================================================

    fun setLogSearchQuery(query: String) {
        _uiState.update { it.copy(logSearchQuery = query) }
    }

    fun setAutoScrollPaused(isPaused: Boolean) {
        _uiState.update { it.copy(isAutoScrollPaused = isPaused) }
    }

    // =========================================================================
    // 5. 会话导出为 Markdown
    // =========================================================================

    fun exportCurrentSession(): String {
        val currentTitle = _uiState.value.availableSessions.find { it.id == _uiState.value.currentSessionId }?.title ?: getApplication<Application>().getString(R.string.vm_006)
        return MarkdownExporter.generateMarkdown(getApplication(), currentTitle, _uiState.value.messages)
    }

    // =========================================================================
    // 6. 连接与收发消息核心调度 (接入真实端点)
    // =========================================================================

    /**
     * v1.6 P0 一键扫码配对：凭扫码得到的配对信息认领设备密钥。
     * 成功后凭据保存到加密存储（Android Keystore），之后用设备密钥连接。
     */
    fun claimPairingByQr(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        desktopName: String,
        onDone: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(statusBanner = getApplication<Application>().getString(R.string.vm_007, desktopName), appError = null)
            }
            // v4.1: E2EE 公钥交换（开关关闭时传空，relay/对端跳过）
            val e2eePubkey = e2eeManager.ownPublicKeyB64() ?: ""
            val result = try {
                PairingClient.claimPairing(getApplication(), relayUrl, accountId, pairingToken, e2eePubkey = e2eePubkey)
            } catch (e: Exception) {
                PairClaimResult(success = false, error = e.message ?: getApplication<Application>().getString(R.string.vm_008))
            }
            // v4.3 M-2: 保存 desktop 的 E2EE 公钥（按 device_id 绑定，HMAC 验签）
            if (result.success && result.e2eePeerPubkey.isNotEmpty() && result.desktopDeviceId.isNotEmpty()) {
                val ok = e2eeManager.storePeerPubkey(
                    result.desktopDeviceId, result.e2eePeerPubkey, result.e2eePubkeySig)
                if (!ok) {
                    _uiState.update { it.copy(appError = AppError(
                        "E2EE_PUBKEY_UNTRUSTED", "对端公钥认证失败，已拒绝（疑似中继篡改）")) }
                } else {
                    // v4.3 M-5: 新配对成功，给一次「已建立加密通道」明确提示
                    _uiState.update { it.copy(showE2eeChannelDialog = true) }
                }
                refreshE2eePeerReady()
            }
            if (result.success && result.deviceSecret.isNotBlank()) {
                // v1.6: 设备密钥保存到加密存储；P0-3: 加密不可用时拒绝保存并报错
                // v4.3 M-2: 扫码配对存的是 device_secret（非主 secret），标记之
                prefsManager.secretIsMaster = false
                val saved = prefsManager.savePairingInfo(
                    result.accountId.ifBlank { accountId },
                    result.deviceSecret,
                    relayUrl
                )
                if (!saved) {
                    _uiState.update {
                        it.copy(
                            appError = AppError("SECURE_STORAGE_UNAVAILABLE", getApplication<Application>().getString(R.string.vm_009)),
                            statusBanner = null
                        )
                    }
                    onDone(false, getApplication<Application>().getString(R.string.vm_010))
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        appMode = AppMode.DESKTOP_RELAY,
                        accountId = result.accountId.ifBlank { accountId },
                        secret = result.deviceSecret,
                        relayUrl = relayUrl,
                        isPaired = true,
                        isAuthenticated = false,
                        appError = null,
                        statusBanner = getApplication<Application>().getString(R.string.vm_011, result.desktopName.ifBlank { desktopName })
                    )
                }
                onDone(true, getApplication<Application>().getString(R.string.vm_012))
                OpLog.record(getApplication(), OpLog.OpType.PAIR, "desktop=${result.desktopName.ifBlank { desktopName }}")
            } else {
                val err = result.error.ifBlank { getApplication<Application>().getString(R.string.vm_013) }
                _uiState.update { it.copy(appError = AppError("PAIR_FAILED", err), statusBanner = null) }
                onDone(false, err)
            }
        }
    }

    fun pairDesktop(accountId: String, secret: String, relayUrl: String) {
        val trimmedAccount = accountId.trim()
        val trimmedSecret = secret.trim()
        val trimmedRelay = relayUrl.trim()

        if (trimmedAccount.isBlank()) {
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", getApplication<Application>().getString(R.string.vm_014))) }
            return
        }
        if (trimmedSecret.isBlank()) {
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", getApplication<Application>().getString(R.string.vm_015))) }
            return
        }

        // v4.3 M-2: 手动配对填的是房间主 secret，可做 E2EE 公钥 HMAC 绑定校验
        prefsManager.secretIsMaster = true
        // P0-3: 加密存储不可用时拒绝保存敏感凭据
        if (!prefsManager.savePairingInfo(trimmedAccount, trimmedSecret, trimmedRelay)) {
            _uiState.update {
                it.copy(appError = AppError("SECURE_STORAGE_UNAVAILABLE", getApplication<Application>().getString(R.string.vm_009)))
            }
            return
        }

        _uiState.update {
            it.copy(
                appMode = AppMode.DESKTOP_RELAY,
                accountId = trimmedAccount,
                secret = trimmedSecret,
                relayUrl = trimmedRelay,
                isPaired = true,
                isAuthenticated = false,
                appError = null,
                diagnostics = null,
                statusBanner = getApplication<Application>().getString(R.string.vm_016)
            )
        }

        // v3.0: 走 Transport（connect/send/close 统一入口）
        relayTransport.connect(
            TransportParams(
                relayUrl = trimmedRelay,
                accountId = trimmedAccount,
                secret = trimmedSecret
            ),
            transportListener
        )
    }

    fun pairCloud(cloudUrl: String, apiKey: String, workspacePath: String) {
        val trimmedUrl = cloudUrl.trim()
        val trimmedKey = apiKey.trim()
        val trimmedWorkspace = workspacePath.trim()

        if (trimmedUrl.isBlank()) {
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", getApplication<Application>().getString(R.string.vm_017))) }
            return
        }

        _uiState.update {
            it.copy(statusBanner = getApplication<Application>().getString(R.string.vm_018))
        }

        cloudClient.checkHealth(trimmedUrl, trimmedKey) { isSuccess, message ->
            if (isSuccess) {
                // P0-3: 加密存储不可用时拒绝保存敏感凭据
                if (!prefsManager.saveCloudConfig(trimmedUrl, trimmedKey, trimmedWorkspace)) {
                    _uiState.update {
                        it.copy(
                            appError = AppError("SECURE_STORAGE_UNAVAILABLE", getApplication<Application>().getString(R.string.vm_019)),
                            statusBanner = null
                        )
                    }
                    return@checkHealth
                }
                _uiState.update {
                    it.copy(
                        appMode = AppMode.CLOUD_HOSTED,
                        cloudServerUrl = trimmedUrl,
                        cloudApiKey = trimmedKey,
                        cloudWorkspacePath = trimmedWorkspace,
                        isPaired = true,
                        isAuthenticated = true,
                        isRelayConnected = true,
                        appError = null,
                        diagnostics = null,
                        statusBanner = null
                    )
                }
                // 拉取云端真实会话列表
                cloudClient.getSessions(trimmedUrl, trimmedKey) { realSessions ->
                    _uiState.update { state ->
                        val currentId = realSessions.firstOrNull()?.id ?: ""
                        state.copy(
                            availableSessions = sortSessions(realSessions),
                            currentSessionId = currentId
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isPaired = false,
                        statusBanner = null,
                        appError = AppError("CLOUD_CHECK_FAILED", message)
                    )
                }
            }
        }
    }

    fun unpair() {
        relayClient.disconnect()
        cloudClient.cancelCurrentStream()
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        _uiState.update {
            it.copy(
                isPaired = false,
                isRelayConnected = false,
                isAuthenticated = false,
                isDesktopOnline = false,
                isGenerating = false,
                isReconnecting = false,
                statusBanner = null
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(appError = null) }
    }

    fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = trimmed
        )

        _uiState.update { state ->
            val updated = (state.messages + userMsg).takeLast(MAX_MESSAGES_COUNT)
            state.copy(
                messages = updated,
                isGenerating = true,
                appError = null,
                isAutoScrollPaused = false
            )
        }
        // v1.6 P0: 任务开始，状态持久化
        setTaskStatus(TaskStatus.RUNNING, getApplication<Application>().getString(R.string.vm_020, trimmed.take(30)))
        // v1.6 P0 任务通知：记录任务信息用于完成通知
        taskStartTimeMs = System.currentTimeMillis()
        taskName = trimmed.take(40)
        taskModifiedFiles.clear()

        OpenCodeKeepAliveService.startTaskProgress(
            getApplication(),
            getApplication<Application>().getString(R.string.vm_020, trimmed.take(30)),
            _uiState.value.currentSessionId
        )

        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            // v1.6 P1: 透传用户选择的 Model/Agent
            // v3.1: 定向路由——开关开且服务端支持且已选目标时才传 target，否则走主 desktop
            val targetId = DesktopRoutingPolicy.resolveTarget(
                flagEnabled = FeatureFlags.ENABLE_DESKTOP_ROUTING,
                serverSupports = relayClient.serverSupports(DesktopRoutingPolicy.CAPABILITY),
                targetDeviceId = _uiState.value.targetDesktopId
            )
            val sid = _uiState.value.currentSessionId
            if (targetId != null && sid.isNotEmpty()) {
                prefsManager.saveSessionDesktopBinding(sid, targetId)
            }
            relayClient.sendPrompt(
                trimmed,
                sid,
                model = _uiState.value.selectedModel,
                agent = _uiState.value.selectedAgent,
                targetDeviceId = targetId
            )
        } else {
            _uiState.update { it.copy(cloudConnectionState = CloudConnectionState.CONNECTING) }
            cloudClient.sendPromptStream(
                baseUrl = _uiState.value.cloudServerUrl,
                apiKey = _uiState.value.cloudApiKey,
                sessionId = _uiState.value.currentSessionId,
                prompt = trimmed,
                listener = this
            )
        }
    }

    fun cancelExecution() {
        OpLog.record(getApplication(), OpLog.OpType.ABORT, "session=" + _uiState.value.currentSessionId)
        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendCancel(_uiState.value.currentSessionId)
        } else {
            cloudClient.abortSessionExecution(
                _uiState.value.cloudServerUrl,
                _uiState.value.cloudApiKey,
                _uiState.value.currentSessionId
            )
            cloudClient.cancelCurrentStream()
        }
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    // v2.5: SSE 重连状态暴露到顶部状态条
    override fun onSseStateChanged(retrying: Boolean, attempt: Int) {
        _uiState.update {
            it.copy(
                cloudConnectionState = if (retrying) CloudConnectionState.RECONNECTING
                else CloudConnectionState.DISCONNECTED
            )
        }
    }

    // ============ v2.5: 多连接 profiles ============

    /** 切换配置：先断开当前连接，再装载 profile 参数 */
    fun switchProfile(profileId: String) {
        val profile = prefsManager.getProfiles().firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch {
            try { relayClient.disconnect() } catch (_: Exception) { }
            try { cloudClient.cancelCurrentStream() } catch (_: Exception) { }
            prefsManager.setActiveProfileId(profileId)
            _uiState.update {
                it.copy(
                    activeProfileId = profileId,
                    appMode = profile.mode,
                    accountId = profile.accountId,
                    secret = prefsManager.getProfileSecret(profileId),
                    relayUrl = profile.relayUrl.ifBlank { it.relayUrl },
                    cloudServerUrl = profile.cloudUrl.ifBlank { it.cloudServerUrl },
                    cloudApiKey = prefsManager.getProfileCloudApiKey(profileId),
                    cloudWorkspacePath = profile.cloudWorkspace,
                    profiles = prefsManager.getProfiles(),
                    isRelayConnected = false,
                    isAuthenticated = false,
                    isDesktopOnline = false,
                    relayConnectionState = RelayConnectionState.DISCONNECTED,
                    cloudConnectionState = CloudConnectionState.DISCONNECTED
                )
            }
        }
    }

    fun addProfile(profile: ConnectionProfile) {
        val list = prefsManager.getProfiles() + profile
        prefsManager.saveProfiles(list)
        _uiState.update { it.copy(profiles = list) }
    }

    fun renameProfile(profileId: String, name: String) {
        prefsManager.getProfiles().firstOrNull { it.id == profileId }?.let {
            prefsManager.updateProfile(it.copy(name = name))
            _uiState.update { s -> s.copy(profiles = prefsManager.getProfiles()) }
        }
    }

    fun deleteProfile(profileId: String): Boolean {
        val ok = prefsManager.deleteProfile(profileId)
        if (ok) _uiState.update { it.copy(profiles = prefsManager.getProfiles(), activeProfileId = prefsManager.getActiveProfileId()) }
        return ok
    }

    // ============ v2.5: 配置导入 / 导出 ============

    /** 导出配置 JSON（不含密钥） */
    fun exportConfigJson(): String {
        OpLog.record(getApplication(), OpLog.OpType.CONFIG_EXPORT, "profiles=" + prefsManager.getProfiles().size)
        return ConfigImportExport.exportJson(prefsManager)
    }

    /**
     * 导入配置 JSON。成功返回 true；导入的 profile 没有密钥，
     * 需提示用户重新配对。
     */
    fun importConfigJson(json: String): Boolean {
        val imported = ConfigImportExport.importJson(prefsManager, json) ?: return false
        OpLog.record(getApplication(), OpLog.OpType.CONFIG_IMPORT, "imported=" + imported.size)
        _uiState.update {
            it.copy(
                profiles = prefsManager.getProfiles(),
                appError = null,
                statusBanner = getApplication<Application>().getString(R.string.vm_021) + imported.size + getApplication<Application>().getString(R.string.vm_022)
            )
        }
        return true
    }

    // --- RelayListener (电脑中继模式真实回调) ---

    override fun onConnected() {
        _uiState.update {
            it.copy(
                isRelayConnected = true,
                isReconnecting = false,
                statusBanner = getApplication<Application>().getString(R.string.vm_023)
            )
        }
    }

    override fun onAuthenticated() {
        _uiState.update {
            it.copy(
                isAuthenticated = true,
                appError = null,
                statusBanner = null
            )
        }
        // P1-1: 认证成功后主动向电脑端查询真实会话列表
        relayClient.sendListSessions()
        // v3.1: 服务端支持 desktop_routing 时拉取在线 desktop 列表；旧 relay 不请求（退回主路由）
        if (FeatureFlags.ENABLE_DESKTOP_ROUTING && relayClient.serverSupports(DesktopRoutingPolicy.CAPABILITY)) {
            relayClient.requestDesktopList()
        }
    }

    override fun onSessionsListReceived(sessions: List<SessionItem>) {
        _uiState.update { state ->
            val merged = if (sessions.isEmpty()) {
                state.availableSessions
            } else {
                sessions.map { s ->
                    val local = state.availableSessions.find { it.id == s.id }
                    if (local != null) {
                        s.copy(tag = local.tag, isPinned = local.isPinned, isArchived = local.isArchived)
                    } else {
                        s
                    }
                }
            }
            prefsManager.saveSessions(merged)
            val currentId = if (merged.any { it.id == state.currentSessionId }) {
                state.currentSessionId
            } else {
                merged.firstOrNull()?.id ?: ""
            }
            state.copy(
                availableSessions = sortSessions(merged),
                currentSessionId = currentId
            )
        }
    }

    // ============ P2-12: 文件浏览器 ============

    /** 打开文件浏览器（path 为空则从桌面端家目录开始） */
    fun openFileBrowser(path: String = "") {
        _uiState.update { it.copy(fileBrowserLoading = true, filePreviewPath = "") }
        relayClient.sendFileList(path)
    }

    fun navigateFileBrowser(path: String) {
        openFileBrowser(path)
    }

    fun closeFilePreview() {
        _uiState.update { it.copy(filePreviewPath = "", filePreviewContent = "", filePreviewTruncated = false) }
    }

    fun previewFile(path: String) {
        _uiState.update { it.copy(fileBrowserLoading = true) }
        relayClient.sendFileRead(path)
    }

    override fun onFileListResult(reqId: String, path: String, entries: List<FileEntry>) {
        _uiState.update {
            it.copy(
                fileBrowserPath = path,
                fileBrowserEntries = entries,
                fileBrowserLoading = false
            )
        }
    }

    override fun onFileReadResult(reqId: String, path: String, content: String, truncated: Boolean) {
        _uiState.update {
            it.copy(
                filePreviewPath = path,
                filePreviewContent = content,
                filePreviewTruncated = truncated,
                fileBrowserLoading = false
            )
        }
    }

    // ============ P2-15: 连接诊断 ============

    fun runDiagnose() {
        _uiState.update { it.copy(diagnoseLoading = true, diagnoseOpencodeOk = null) }
        relayClient.sendDiagnose()
    }

    override fun onDiagnoseResult(reqId: String, opencodeOk: Boolean, version: String, error: String) {
        _uiState.update {
            it.copy(
                diagnoseLoading = false,
                diagnoseOpencodeOk = opencodeOk,
                diagnoseOpencodeVersion = version,
                diagnoseOpencodeError = error
            )
        }
    }

    override fun onAuthError(error: String) {
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                isRelayConnected = false,
                isGenerating = false,
                isPaired = false,
                appError = AppError("AUTH_FAILED", error),
                statusBanner = null
            )
        }
    }

    override fun onDisconnected(reason: String) {
        _uiState.update {
            it.copy(
                isRelayConnected = false,
                isAuthenticated = false,
                isDesktopOnline = false,
                isGenerating = false
            )
        }
    }

    override fun onReconnecting(delayMs: Long) {
        _uiState.update {
            it.copy(
                isReconnecting = true,
                isRelayConnected = false,
                statusBanner = getApplication<Application>().getString(R.string.vm_024, (delayMs + 500) / 1000)
            )
        }
    }

    override fun onDesktopStatusChanged(isOnline: Boolean) {
        _uiState.update { it.copy(isDesktopOnline = isOnline) }
        if (isOnline && _uiState.value.isAuthenticated) {
            relayClient.sendListSessions()
        }
    }

    // P0-4: 连接状态机——UI 据此区分网络、鉴权、Desktop 的不同故障
    override fun onConnectionStateChanged(state: RelayConnectionState) {
        _uiState.update { s ->
            s.copy(
                relayConnectionState = state,
                isRelayConnected = when (state) {
                    RelayConnectionState.CONNECTED,
                    RelayConnectionState.AUTHENTICATING,
                    RelayConnectionState.AUTHENTICATED,
                    RelayConnectionState.DESKTOP_ONLINE -> true
                    else -> false
                },
                isReconnecting = state == RelayConnectionState.RECONNECTING,
                statusBanner = when (state) {
                    RelayConnectionState.CONNECTING -> getApplication<Application>().getString(R.string.vm_016)
                    RelayConnectionState.CONNECTED -> getApplication<Application>().getString(R.string.vm_023)
                    RelayConnectionState.AUTHENTICATING -> getApplication<Application>().getString(R.string.vm_025)
                    RelayConnectionState.RECONNECTING -> s.statusBanner // 保持重连倒计时文案
                    RelayConnectionState.AUTH_FAILED -> null
                    RelayConnectionState.DISCONNECTED -> null
                    else -> s.statusBanner
                }
            )
        }
    }

    override fun onToolApprovalRequest(request: ToolApprovalRequest) {
        _uiState.update { it.copy(pendingApproval = request) }
        // v1.6 P0: 统计修改文件 + 明确进入"权限审批"状态
        request.filePath?.takeIf { it.isNotBlank() }?.let { taskModifiedFiles.add(it) }
        setTaskStatus(TaskStatus.APPROVAL_REQUIRED, getApplication<Application>().getString(R.string.vm_026, request.toolName))
        OpenCodeKeepAliveService.notifyApprovalRequired(
            getApplication(),
            getApplication<Application>().getString(R.string.vm_tool_desc, request.toolName, request.filePath ?: getApplication<Application>().getString(R.string.vm_code_change))
        )
    }

    override fun onStreamStart(sessionId: String) {
        _uiState.update { it.copy(cloudConnectionState = CloudConnectionState.STREAMING) }
        val newMsgId = UUID.randomUUID().toString()
        activeAssistantMessageId = newMsgId
        // P1-5: 新一轮流式输出，清空上一轮缓冲
        streamBuffer.clear()
        streamFlushJob?.cancel()
        streamFlushSessionId = sessionId

        val placeholderMsg = ChatMessage(
            id = newMsgId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )

        _uiState.update { state ->
            val updated = (state.messages + placeholderMsg).takeLast(MAX_MESSAGES_COUNT)
            state.copy(
                messages = updated,
                isGenerating = true
            )
        }
    }

    override fun onStreamChunk(sessionId: String, chunk: String) {
        if (activeAssistantMessageId == null) {
            onStreamStart(sessionId)
        }

        // P1-5: chunk 先进缓冲，50ms 批量刷新一次，避免每个 chunk 重建消息列表
        streamBuffer.append(chunk)
        streamFlushSessionId = sessionId
        // 通知栏进度也节流（最多 1 秒一次）
        val now = System.currentTimeMillis()
        if (now - lastProgressNotifyMs > 1000) {
            lastProgressNotifyMs = now
            OpenCodeKeepAliveService.updateProgress(getApplication(), getApplication<Application>().getString(R.string.vm_027))
        }
        if (streamFlushJob?.isActive != true) {
            streamFlushJob = viewModelScope.launch {
                delay(STREAM_FLUSH_MS)
                flushStreamBuffer()
            }
        }
    }

    /**
     * P1-5: 将缓冲的 chunk 一次性追加到流式消息，保证顺序、不丢失、不重复。
     */
    private fun flushStreamBuffer() {
        val text = streamBuffer.toString()
        if (text.isEmpty()) return
        streamBuffer.clear()
        val msgId = activeAssistantMessageId ?: return
        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == msgId) {
                    msg.copy(content = applyStreamGuard(msg.content + text))
                } else {
                    msg
                }
            }
            state.copy(messages = updatedMessages)
        }
    }

    /** P1-5: 超长输出折叠保护（原 onStreamChunk 内联逻辑抽取，行为不变） */
    private fun applyStreamGuard(combined: String): String {
        val lines = combined.split("\n")
        return if (lines.size > MAX_STREAM_LINES) {
            val header = lines.take(50).joinToString("\n")
            val tail = lines.takeLast(MAX_STREAM_LINES - 50).joinToString("\n")
            getApplication<Application>().getString(R.string.vm_028, header, lines.size - MAX_STREAM_LINES, tail)
        } else {
            combined
        }
    }

    override fun onStreamEnd(sessionId: String) {
        _uiState.update { it.copy(cloudConnectionState = CloudConnectionState.DISCONNECTED) }
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        // P1-5: 流结束前把缓冲剩余 chunk 全部刷入，保证不丢失
        streamFlushJob?.cancel()
        flushStreamBuffer()
        // v1.6 P0: 任务完成
        setTaskStatus(TaskStatus.COMPLETED, getApplication<Application>().getString(R.string.vm_029))
        // v1.6 P0 任务通知：完成通知（任务名、耗时、修改文件数，点击直达会话）
        val durationMs = if (taskStartTimeMs > 0) System.currentTimeMillis() - taskStartTimeMs else 0L
        OpenCodeKeepAliveService.notifyTaskCompleted(
            getApplication(),
            taskName.ifBlank { "OpenCode" },
            durationMs,
            taskModifiedFiles.size,
            sessionId
        )
        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == activeAssistantMessageId) {
                    msg.copy(isStreaming = false)
                } else {
                    msg
                }
            }
            state.copy(
                messages = updatedMessages,
                isGenerating = false
            )
        }
        activeAssistantMessageId = null
    }

    override fun onAppError(code: String, message: String) {
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        // v1.6 P0: 任务失败状态 + 失败通知
        setTaskStatus(TaskStatus.FAILED, getApplication<Application>().getString(R.string.vm_030, message))
        OpenCodeKeepAliveService.notifyTaskFailed(
            getApplication(),
            message,
            _uiState.value.currentSessionId
        )
        _uiState.update { state ->
            val errorMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = getApplication<Application>().getString(R.string.vm_031, code, message),
                isError = true
            )
            state.copy(
                messages = (state.messages + errorMsg).takeLast(MAX_MESSAGES_COUNT),
                isGenerating = false,
                appError = AppError(code, message)
            )
        }
    }

    override fun onError(error: String) {
        _uiState.update { it.copy(appError = AppError("NETWORK_ERROR", error)) }
    }

    /**
     * v2.3: 重同步语义——收到 resync_required / epoch 变化时调用。
     * 不再按任务失败处理：不中止进度、不弹失败通知，只追加一条同步提示；
     * 服务端会自动补发缓冲消息（epoch 重置后 lastRelaySeq=0，全量补发）。
     */
    override fun onResyncRequired(message: String) {
        _uiState.update { state ->
            val notice = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = getApplication<Application>().getString(R.string.vm_032, message),
                isError = false
            )
            state.copy(messages = (state.messages + notice).takeLast(MAX_MESSAGES_COUNT))
        }
    }

    /**
     * v2.3: 写操作未确认（socket 不可用）。显示「未确认」提示，由用户手动重试，
     * 绝不自动重发 prompt（避免断线重连后产生重复任务）。
     */
    override fun onWriteUnconfirmed(action: String, clientMsgId: String) {
        val what = when (action) {
            "send_prompt" -> getApplication<Application>().getString(R.string.vm_033)
            "cancel" -> getApplication<Application>().getString(R.string.vm_034)
            else -> getApplication<Application>().getString(R.string.vm_035)
        }
        _uiState.update { state ->
            val notice = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = getApplication<Application>().getString(R.string.vm_036, what),
                isError = true
            )
            state.copy(
                messages = (state.messages + notice).takeLast(MAX_MESSAGES_COUNT),
                isGenerating = false
            )
        }
    }

    /**
     * v1.6 P0 任务通知：AI 等待用户输入（高优先级通知 + 状态）
     */
    override fun onWaitingInput(sessionId: String, prompt: String) {
        setTaskStatus(TaskStatus.WAITING_INPUT, getApplication<Application>().getString(R.string.vm_037, prompt.take(40)))
        OpenCodeKeepAliveService.notifyWaitingInput(
            getApplication(),
            prompt,
            sessionId
        )
    }

    // =========================================================================
    // v1.6 P0 多设备管理
    // =========================================================================

    fun requestDeviceList() {
        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.requestDeviceList()
        }
    }

    // v2.4: 按 deviceId 撤销
    fun revokeDevice(deviceId: String, deviceName: String = "") {
        OpLog.record(getApplication(), OpLog.OpType.REVOKE, "device=$deviceName")
        relayClient.revokeDevice(deviceId, deviceName)
    }

    // v2.4: 按 deviceId 重命名
    fun renameDevice(deviceId: String, oldName: String, newName: String) {
        relayClient.renameDeviceById(deviceId, oldName, newName)
    }

    override fun onDeviceListReceived(devices: List<DeviceInfo>) {
        _uiState.update { it.copy(pairedDevices = devices) }
    }

    override fun onDeviceRevoked(deviceName: String) {
        // v4.1: 撤销设备时清理其 E2EE 公钥（按 deviceName 查 deviceId）
        if (deviceName.isNotBlank()) {
            _uiState.value.pairedDevices.firstOrNull { it.deviceName == deviceName }
                ?.deviceId?.ifEmpty { null }?.let { e2eeManager.removePeer(it) }
        }
        // 撤销后刷新列表
        requestDeviceList()
        _uiState.update {
            it.copy(statusBanner = if (deviceName.isNotBlank()) getApplication<Application>().getString(R.string.vm_038, deviceName) else null)
        }
    }

    override fun onDeviceRenamed(deviceName: String) {
        requestDeviceList()
    }

    // =========================================================================
    // v3.1 多 desktop 定向路由
    // =========================================================================

    /** 打开目标电脑选择列表（Dialog 打开时会自动刷新） */
    fun openDesktopList() {
        _uiState.update { it.copy(showDesktopList = true) }
    }

    /** v3.2: 关闭安全存储迁移回退提示（只提示一次） */
    fun dismissSecureMigrationNotice() {
        prefsManager.dismissSecureMigrationNotice()
        _uiState.update { it.copy(showSecureMigrationNotice = false) }
    }

    // v4.0: 检测到 v3 旧服务端（hello_ack v<4）→ 一次性升级提示（功能可用，不阻断）
    override fun onOldRelayVersionDetected() {
        val url = _uiState.value.relayUrl
        if (!prefsManager.wasOldRelayWarnDismissed(url)) {
            _uiState.update { it.copy(showOldRelayWarning = true) }
        }
    }

    /** v4.2: E2EE 运行时开关 */
    fun setE2eeEnabled(enabled: Boolean) {
        prefsManager.isE2eeEnabled = enabled
        AppLog.i("E2EE", "开关: $enabled")
        refreshE2eePeerReady()
    }

    /** v4.3 M-5: 关闭「已建立加密通道」提示框 */
    fun dismissE2eeChannelDialog() {
        _uiState.update { it.copy(showE2eeChannelDialog = false) }
    }

    /** v4.0: 关闭 v3 旧服务端提示（每个 relayUrl 只提示一次） */
    fun dismissOldRelayWarning() {
        prefsManager.dismissOldRelayWarn(_uiState.value.relayUrl)
        _uiState.update { it.copy(showOldRelayWarning = false) }
    }

    fun closeDesktopList() {
        _uiState.update { it.copy(showDesktopList = false) }
    }

    /** 刷新在线 desktop 列表（开关关闭或旧 relay 时不请求） */
    fun refreshDesktopList() {
        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY &&
            FeatureFlags.ENABLE_DESKTOP_ROUTING &&
            relayClient.serverSupports(DesktopRoutingPolicy.CAPABILITY)
        ) {
            relayClient.requestDesktopList()
        }
    }

    override fun onDesktopList(desktops: List<DesktopInfo>) {
        val prevTarget = _uiState.value.targetDesktopId
        // 已选目标不在在线列表中 → 清空选择，回主 desktop 路由
        val targetAlive = prevTarget.isEmpty() || desktops.any { it.deviceId == prevTarget }
        if (!targetAlive) {
            prefsManager.saveTargetDesktopId("")
            AppLog.i("DesktopRouting", "target $prevTarget offline, fallback to primary")
        }
        _uiState.update {
            it.copy(
                desktopList = desktops,
                targetDesktopId = if (targetAlive) prevTarget else ""
            )
        }
        refreshE2eePeerReady()
    }

    /** v4.3 M-5: 按当前目标（targetDesktopId 或主 desktop）刷新 E2EE 就绪状态。 */
    private fun refreshE2eePeerReady() {
        val s = _uiState.value
        val target = s.targetDesktopId.ifEmpty {
            s.desktopList.firstOrNull { it.isPrimary }?.deviceId.orEmpty()
        }
        val ready = target.isNotEmpty() && e2eeManager.hasPeerKey(target)
        if (s.e2eePeerReady != ready) {
            _uiState.update { it.copy(e2eePeerReady = ready) }
        }
    }

    override fun onTargetDesktopOffline(targetDeviceId: String, message: String) {
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        val targetName = _uiState.value.desktopList
            .firstOrNull { it.deviceId == targetDeviceId }
            ?.deviceName.orEmpty()
        val app = getApplication<Application>()
        val hint = DesktopRoutingPolicy.offlineHint(
            targetName, targetDeviceId, message,
            app.getString(R.string.route_001),
            app.getString(R.string.route_002),
            app.getString(R.string.route_003)
        )
        AppLog.w("DesktopRouting", "target offline: $hint")
        _uiState.update { state ->
            val sysMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = getApplication<Application>().getString(R.string.vm_039, hint),
                isError = true
            )
            state.copy(
                messages = (state.messages + sysMsg).takeLast(MAX_MESSAGES_COUNT),
                isGenerating = false,
                // 复用既有 appError 展示机制
                appError = AppError("DESKTOP_OFFLINE", hint),
                targetOfflineHint = hint
            )
        }
    }

    fun clearTargetOfflineHint() {
        _uiState.update { it.copy(targetOfflineHint = null) }
    }

    /**
     * 请求切换目标电脑。有活跃会话（生成中或存在当前会话）时先弹确认，
     * 由 UI 层根据 pendingTargetSwitch 展示确认对话框。
     */
    fun requestTargetSwitch(deviceId: String) {
        val st = _uiState.value
        if (st.isGenerating || st.currentSessionId.isNotEmpty()) {
            _uiState.update { it.copy(pendingTargetSwitch = deviceId) }
        } else {
            applyTargetSwitch(deviceId)
        }
    }

    fun confirmTargetSwitch() {
        val pending = _uiState.value.pendingTargetSwitch ?: return
        _uiState.update { it.copy(pendingTargetSwitch = null) }
        applyTargetSwitch(pending)
    }

    fun cancelTargetSwitch() {
        _uiState.update { it.copy(pendingTargetSwitch = null) }
    }

    private fun applyTargetSwitch(deviceId: String) {
        prefsManager.saveTargetDesktopId(deviceId)
        val sid = _uiState.value.currentSessionId
        if (sid.isNotEmpty()) {
            prefsManager.saveSessionDesktopBinding(sid, deviceId)
        }
        _uiState.update {
            it.copy(
                targetDesktopId = deviceId,
                showDesktopList = false,
                targetOfflineHint = null,
                appError = null
            )
        }
    }

    // =========================================================================
    // v1.6 P1 项目管理中心
    // =========================================================================

    fun requestProjects() {
        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.requestProjects()
        }
    }

    fun toggleFavoriteProject(projectId: String) {
        val current = _uiState.value.favoriteProjectIds.toMutableSet()
        if (current.contains(projectId)) current.remove(projectId) else current.add(projectId)
        prefsManager.saveFavoriteProjects(current.toList())
        _uiState.update { it.copy(favoriteProjectIds = current) }
    }

    override fun onProjectsDataReceived(projects: List<ProjectInfo>, projectsError: String?) {
        _uiState.update {
            it.copy(
                projects = projects,
                projectsError = projectsError,
                favoriteProjectIds = prefsManager.getFavoriteProjects().toSet()
            )
        }
    }

    // =========================================================================
    // v1.6 P1 Model/Agent 管理
    // =========================================================================

    fun requestModelConfig() {
        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.requestConfig()
        }
    }

    fun selectAgent(agent: AgentInfo?) {
        _uiState.update { it.copy(selectedAgent = agent) }
        prefsManager.saveSelectedAgent(agent?.id ?: "")
    }

    fun selectModel(model: ModelInfo?) {
        _uiState.update { it.copy(selectedModel = model) }
        prefsManager.saveSelectedModel(
            model?.providerId ?: "", model?.modelId ?: ""
        )
    }

    override fun onConfigDataReceived(agents: List<AgentInfo>, models: List<ModelInfo>, configError: String?) {
        // 恢复上次选择
        val savedAgentId = prefsManager.getSelectedAgent()
        val (savedProvider, savedModel) = prefsManager.getSelectedModel()
        val agent = agents.find { it.id == savedAgentId }
        val model = models.find { it.providerId == savedProvider && it.modelId == savedModel }
        _uiState.update {
            it.copy(
                availableAgents = agents,
                availableModels = models,
                configError = configError,
                selectedAgent = agent ?: it.selectedAgent,
                selectedModel = model ?: it.selectedModel
            )
        }
    }

    override fun onError(code: String, message: String) {
        _uiState.update { it.copy(cloudConnectionState = CloudConnectionState.DISCONNECTED) }
        onAppError(code, message)
    }

    override fun onCleared() {
        super.onCleared()
        // v1.6 P0 后台保活：ViewModel 销毁（Activity 退出）不再断开连接。
        // 连接由 Application 持有，前台服务保活进程，任务在后台继续。
        // 用户主动断开请调用 disconnectAll()。
    }

    /**
     * v1.6: 用户主动断开所有连接（退出登录/切换账号时调用）。
     */
    fun disconnectAll() {
        relayClient.disconnect()
        cloudClient.cancelCurrentStream()
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        _uiState.update { it.copy(isPaired = false, isAuthenticated = false) }
    }
}
