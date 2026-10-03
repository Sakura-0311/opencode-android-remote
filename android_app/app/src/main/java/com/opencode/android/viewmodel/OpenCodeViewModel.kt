package com.opencode.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.CloudStreamListener
import com.opencode.android.network.RelayListener
import com.opencode.android.network.RelayWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class OpenCodeViewModel(application: Application) : AndroidViewModel(application), RelayListener, CloudStreamListener {

    private val prefsManager = PreferencesManager(application.applicationContext)
    private val relayClient = RelayWebSocketClient()
    private val cloudClient = CloudApiClient()

    private val _uiState = MutableStateFlow(
        OpenCodeUiState(
            appMode = prefsManager.getAppMode(),
            accountId = prefsManager.getAccountId(),
            secret = prefsManager.getSecret(),
            relayUrl = prefsManager.getRelayUrl(),
            cloudServerUrl = prefsManager.getCloudServerUrl(),
            cloudApiKey = prefsManager.getCloudApiKey(),
            cloudWorkspacePath = prefsManager.getCloudWorkspacePath()
        )
    )
    val uiState: StateFlow<OpenCodeUiState> = _uiState.asStateFlow()

    private var activeAssistantMessageId: String? = null

    companion object {
        private const val MAX_MESSAGES_COUNT = 500
        private const val MAX_STREAM_LINES = 2000
    }

    fun switchMode(mode: AppMode) {
        prefsManager.saveAppMode(mode)
        _uiState.update { it.copy(appMode = mode, appError = null) }
    }

    // --- 模式 1: 电脑中继连接 ---
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
                statusBanner = "正在连接中继服务器..."
            )
        }

        relayClient.connect(trimmedRelay, trimmedAccount, trimmedSecret, this)
    }

    // --- 模式 2: 云端工作区直连 ---
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
                        isDesktopOnline = true, // 云端模式下即表示云端主机在线
                        isRelayConnected = true,
                        appError = null,
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
                appError = null
            )
        }

        if (_uiState.value.appMode == AppMode.DESKTOP_RELAY) {
            relayClient.sendPrompt(trimmed, _uiState.value.currentSessionId)
        } else {
            // 云端模式直接通过 HTTP/SSE 调度
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

    // --- CloudStreamListener (云端直接调用错误回调) ---
    override fun onError(code: String, message: String) {
        onAppError(code, message)
    }

    override fun onCleared() {
        super.onCleared()
        relayClient.disconnect()
        cloudClient.cancelCurrentStream()
    }
}
