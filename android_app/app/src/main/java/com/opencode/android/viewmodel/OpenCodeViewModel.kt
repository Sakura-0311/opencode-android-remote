package com.opencode.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.opencode.android.data.model.ToolApprovalRequest
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.CloudStreamListener
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
    private val relayClient = RelayWebSocketClient()
    private val cloudClient = CloudApiClient()

    private val _uiState: MutableStateFlow<OpenCodeUiState>

    init {
        // v1.6 P0 断线恢复：relay 消息序号持久化
        relayClient.setSeqPersistence(
            application.getSharedPreferences("relay_seq_store", android.content.Context.MODE_PRIVATE)
        )
        // v1.6 P0 断线恢复：云端 SSE 游标持久化
        cloudClient.setEventIdPersistence(
            application.getSharedPreferences("sse_event_store", android.content.Context.MODE_PRIVATE)
        )
        // P1-1: 彻底移除虚假写死的 Demo 会话数据，以真实服务拉取为准
        val savedSessions = prefsManager.getSavedSessions()

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
                currentSessionId = savedSessions.firstOrNull()?.id ?: ""
            )
        )
    }

    val uiState: StateFlow<OpenCodeUiState> = _uiState.asStateFlow()

    private var activeAssistantMessageId: String? = null

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

        OpenCodeKeepAliveService.startTaskProgress(getApplication(), "执行指令: ${trimmed.take(30)}...")

        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendPrompt(trimmed, _uiState.value.currentSessionId)
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

    override fun onError(code: String, message: String) {
        onAppError(code, message)
    }

    override fun onCleared() {
        super.onCleared()
        relayClient.disconnect()
        cloudClient.cancelCurrentStream()
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
    }
}
