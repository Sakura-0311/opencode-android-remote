package com.opencode.android.viewmodel

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
import com.opencode.android.network.DeviceInfo
import com.opencode.android.network.AgentInfo
import com.opencode.android.network.ModelInfo
import com.opencode.android.network.ProjectInfo
import com.opencode.android.network.PairClaimResult
import com.opencode.android.network.PairingClient
import com.opencode.android.network.RelayListener
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.network.TunnelDiagnosticsHelper
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.util.MarkdownExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.util.UUID

class OpenCodeViewModel(application: Application) : AndroidViewModel(application), RelayListener, CloudStreamListener {

    private val prefsManager = PreferencesManager(application.applicationContext)
    // v1.6 P0 后台保活：连接由 Application 持有，与 ViewModel 生命周期解耦
    private val app = application as OpenCodeApp
    private val relayClient: RelayWebSocketClient = app.relayClient
    private val cloudClient: CloudApiClient = app.cloudClient

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
                isGenerating = status == TaskStatus.RUNNING
                        || status == TaskStatus.WAITING_INPUT
                        || status == TaskStatus.APPROVAL_REQUIRED
            )
        }
    }

    init {
        // v1.6: 序号持久化已在 OpenCodeApp.onCreate 中初始化
        // v1.6 P0 后台保活：ViewModel 重建时重新挂载到应用级连接（不断连）
        // CloudApiClient 每次调用时传入 listener，无需重新挂载
        relayClient.setListener(this)
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
            "应用重启，连接已断开。${savedDetail}"
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
                availableSessions = savedSessions,
                currentSessionId = savedSessions.firstOrNull()?.id ?: "",
                taskStatus = effectiveStatus,
                taskStatusDetail = effectiveDetail
            )
        )
    }

    val uiState: StateFlow<OpenCodeUiState> = _uiState.asStateFlow()

    private var activeAssistantMessageId: String? = null
    // v1.6 P0 任务通知：任务计时与文件统计
    private var taskStartTimeMs: Long = 0L
    private var taskName: String = ""
    private val taskModifiedFiles = mutableSetOf<String>()

    companion object {
        private const val MAX_MESSAGES_COUNT = 500
        private const val MAX_STREAM_LINES = 2000
    }

    fun switchMode(mode: AppMode) {
        prefsManager.saveAppMode(mode)
        _uiState.update { it.copy(appMode = mode, appError = null, diagnostics = null) }
    }

    // =========================================================================
    // 1. 会话分组、标签管理、置顶与归档 (基于真实 Session 数据)
    // =========================================================================

    fun setTagFilter(tag: String?) {
        val finalTag = if (tag == "全部") null else tag
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
            summary = "重构 Token 校验逻辑，引入高可靠性 JWT 签名算法",
            diffLines = sampleDiff
        )
        _uiState.update { it.copy(pendingApproval = approval) }
        OpenCodeKeepAliveService.notifyApprovalRequired(getApplication(), "edit_file: AuthService.kt")
    }

    fun approveTool(callId: String) {
        val state = _uiState.value
        val nonce = state.pendingApproval?.nonce
        _uiState.update { it.copy(pendingApproval = null) }
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
        // P0-3 修复：回传真实拒绝决定；B-9: 云端模式同上
        if (state.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendApprovalResponse(callId, false, "用户在手机端拒绝了修改", nonce)
        } else if (state.appMode == AppMode.CLOUD_HOSTED) {
            cloudClient.respondToPermission(
                state.cloudServerUrl, state.cloudApiKey,
                state.currentSessionId, callId, false, "用户在手机端拒绝了修改"
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
                diagnostics = DiagnosticsResult(isChecking = true, statusTitle = "正在诊断连通性...")
            )
        }

        TunnelDiagnosticsHelper.diagnoseEndpoint(targetUrl, key) { result ->
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
        val currentTitle = _uiState.value.availableSessions.find { it.id == _uiState.value.currentSessionId }?.title ?: "OpenCode 会话"
        return MarkdownExporter.generateMarkdown(currentTitle, _uiState.value.messages)
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
                it.copy(statusBanner = "正在与 $desktopName 配对…", appError = null)
            }
            val result = try {
                PairingClient.claimPairing(relayUrl, accountId, pairingToken)
            } catch (e: Exception) {
                PairClaimResult(success = false, error = e.message ?: "配对异常")
            }
            if (result.success && result.deviceSecret.isNotBlank()) {
                // v1.6: 设备密钥保存到加密存储
                prefsManager.savePairingInfo(
                    result.accountId.ifBlank { accountId },
                    result.deviceSecret,
                    relayUrl
                )
                _uiState.update {
                    it.copy(
                        appMode = AppMode.DESKTOP_RELAY,
                        accountId = result.accountId.ifBlank { accountId },
                        secret = result.deviceSecret,
                        relayUrl = relayUrl,
                        isPaired = true,
                        isAuthenticated = false,
                        appError = null,
                        statusBanner = "已与 ${result.desktopName.ifBlank { desktopName }} 配对成功，正在连接…"
                    )
                }
                onDone(true, "配对成功")
            } else {
                val err = result.error.ifBlank { "配对失败，请重新扫码" }
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
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", "请输入有效的账号/配对码")) }
            return
        }
        if (trimmedSecret.isBlank()) {
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", "请输入电脑端启动时显示的 Secret 密钥")) }
            return
        }

        prefsManager.savePairingInfo(trimmedAccount, trimmedSecret, trimmedRelay)

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
                statusBanner = "正在连接中继服务器..."
            )
        }

        relayClient.connect(trimmedRelay, trimmedAccount, trimmedSecret, this)
    }

    fun pairCloud(cloudUrl: String, apiKey: String, workspacePath: String) {
        val trimmedUrl = cloudUrl.trim()
        val trimmedKey = apiKey.trim()
        val trimmedWorkspace = workspacePath.trim()

        if (trimmedUrl.isBlank()) {
            _uiState.update { it.copy(appError = AppError("INPUT_EMPTY", "请输入云端 OpenCode 服务地址")) }
            return
        }

        _uiState.update {
            it.copy(statusBanner = "正在探测云端 /global/health 真实健康状态...")
        }

        cloudClient.checkHealth(trimmedUrl, trimmedKey) { isSuccess, message ->
            if (isSuccess) {
                prefsManager.saveCloudConfig(trimmedUrl, trimmedKey, trimmedWorkspace)
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
        setTaskStatus(TaskStatus.RUNNING, "执行指令: ${trimmed.take(30)}...")
        // v1.6 P0 任务通知：记录任务信息用于完成通知
        taskStartTimeMs = System.currentTimeMillis()
        taskName = trimmed.take(40)
        taskModifiedFiles.clear()

        OpenCodeKeepAliveService.startTaskProgress(
            getApplication(),
            "执行指令: ${trimmed.take(30)}...",
            _uiState.value.currentSessionId
        )

        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            // v1.6 P1: 透传用户选择的 Model/Agent
            relayClient.sendPrompt(
                trimmed,
                _uiState.value.currentSessionId,
                model = _uiState.value.selectedModel,
                agent = _uiState.value.selectedAgent
            )
        } else {
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

    // --- RelayListener (电脑中继模式真实回调) ---

    override fun onConnected() {
        _uiState.update {
            it.copy(
                isRelayConnected = true,
                isReconnecting = false,
                statusBanner = "中继已连通，正在验证 Secret..."
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
                statusBanner = "中继中断，将在 ${(delayMs + 500) / 1000} 秒后自动重试..."
            )
        }
    }

    override fun onDesktopStatusChanged(isOnline: Boolean) {
        _uiState.update { it.copy(isDesktopOnline = isOnline) }
        if (isOnline && _uiState.value.isAuthenticated) {
            relayClient.sendListSessions()
        }
    }

    override fun onToolApprovalRequest(request: ToolApprovalRequest) {
        _uiState.update { it.copy(pendingApproval = request) }
        // v1.6 P0: 统计修改文件 + 明确进入"权限审批"状态
        request.filePath?.takeIf { it.isNotBlank() }?.let { taskModifiedFiles.add(it) }
        setTaskStatus(TaskStatus.APPROVAL_REQUIRED, "等待审批: ${request.toolName}")
        OpenCodeKeepAliveService.notifyApprovalRequired(
            getApplication(),
            "${request.toolName}: ${request.filePath ?: "代码修改"}"
        )
    }

    override fun onStreamStart(sessionId: String) {
        val newMsgId = UUID.randomUUID().toString()
        activeAssistantMessageId = newMsgId

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

        OpenCodeKeepAliveService.updateProgress(getApplication(), "AI 正在生成/执行: ${chunk.take(30)}...")

        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == activeAssistantMessageId) {
                    val combined = msg.content + chunk
                    val lines = combined.split("\n")
                    val guardedContent = if (lines.size > MAX_STREAM_LINES) {
                        val header = lines.take(50).joinToString("\n")
                        val tail = lines.takeLast(MAX_STREAM_LINES - 50).joinToString("\n")
                        "$header\n\n... [已自动折叠中间超长日志 (${lines.size - MAX_STREAM_LINES} 行)] ...\n\n$tail"
                    } else {
                        combined
                    }
                    msg.copy(content = guardedContent)
                } else {
                    msg
                }
            }
            state.copy(messages = updatedMessages)
        }
    }

    override fun onStreamEnd(sessionId: String) {
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        // v1.6 P0: 任务完成
        setTaskStatus(TaskStatus.COMPLETED, "任务已完成")
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
        setTaskStatus(TaskStatus.FAILED, "任务失败: $message")
        OpenCodeKeepAliveService.notifyTaskFailed(
            getApplication(),
            message,
            _uiState.value.currentSessionId
        )
        _uiState.update { state ->
            val errorMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = "【错误 $code】 $message",
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
     * v1.6 P0 任务通知：AI 等待用户输入（高优先级通知 + 状态）
     */
    override fun onWaitingInput(sessionId: String, prompt: String) {
        setTaskStatus(TaskStatus.WAITING_INPUT, "等待输入: ${prompt.take(40)}")
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

    fun revokeDevice(deviceName: String) {
        relayClient.revokeDevice(deviceName)
    }

    fun renameDevice(oldName: String, newName: String) {
        relayClient.renameDevice(oldName, newName)
    }

    override fun onDeviceListReceived(devices: List<DeviceInfo>) {
        _uiState.update { it.copy(pairedDevices = devices) }
    }

    override fun onDeviceRevoked(deviceName: String) {
        // 撤销后刷新列表
        requestDeviceList()
        _uiState.update {
            it.copy(statusBanner = if (deviceName.isNotBlank()) "已撤销设备：$deviceName" else null)
        }
    }

    override fun onDeviceRenamed(deviceName: String) {
        requestDeviceList()
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
