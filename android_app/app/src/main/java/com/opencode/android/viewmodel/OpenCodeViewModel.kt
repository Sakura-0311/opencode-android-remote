package com.opencode.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
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
import com.opencode.android.network.RelayListener
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.network.TunnelDiagnosticsHelper
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.util.MarkdownExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class OpenCodeViewModel(application: Application) : AndroidViewModel(application), RelayListener, CloudStreamListener {

    private val prefsManager = PreferencesManager(application.applicationContext)
    private val relayClient = RelayWebSocketClient()
    private val cloudClient = CloudApiClient()

    private val _uiState: MutableStateFlow<OpenCodeUiState>

    init {
        val savedSessions = prefsManager.getSavedSessions().ifEmpty {
            listOf(
                SessionItem("default", "Main Workspace", tag = "默认", isPinned = true),
                SessionItem("debug_session", "API 认证异常排查", tag = "代码调试"),
                SessionItem("auto_deploy", "云端 Docker 自动化脚本", tag = "自动化任务")
            )
        }

        _uiState = MutableStateFlow(
            OpenCodeUiState(
                appMode = prefsManager.getAppMode(),
                accountId = prefsManager.getAccountId(),
                secret = prefsManager.getSecret(),
                relayUrl = prefsManager.getRelayUrl(),
                cloudServerUrl = prefsManager.getCloudServerUrl(),
                cloudApiKey = prefsManager.getCloudApiKey(),
                cloudWorkspacePath = prefsManager.getCloudWorkspacePath(),
                availableSessions = savedSessions
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
    // 1. 会话分组、标签管理、置顶与归档
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
        // 一键归档非置顶的旧会话
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
    // 2. 工具审批与代码 Diff 预览
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
        _uiState.update { it.copy(pendingApproval = null) }
        relayClient.sendApprovalResponse(callId, true)
        sendMessage("/approve $callId")
    }

    fun rejectTool(callId: String) {
        _uiState.update { it.copy(pendingApproval = null) }
        relayClient.sendApprovalResponse(callId, false, "用户拒绝了本次文件修改")
        sendMessage("/reject $callId - 用户拒绝了本次文件修改")
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
    // 6. 连接与收发消息核心调度
    // =========================================================================

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
            it.copy(statusBanner = "正在探测云端服务健康状态...")
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
                        isDesktopOnline = true,
                        isRelayConnected = true,
                        appError = null,
                        diagnostics = null,
                        statusBanner = null
                    )
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

        // 启动后台前台保活服务与常驻进度
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
            cloudClient.cancelCurrentStream()
        }
        OpenCodeKeepAliveService.stopTaskProgress(getApplication())
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    // --- RelayListener (电脑中继模式回调) ---

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
