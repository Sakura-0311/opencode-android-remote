package com.opencode.android.data.model

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AppMode {
    DESKTOP_RELAY, // 模式 1: 电脑远程控制 (通过 Relay 操控家中/办公室 PC)
    CLOUD_HOSTED   // 模式 2: 云端工作区直连 (无需开电脑，直接使用云端 OpenCode 实例)
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolEvents: List<String> = emptyList(),
    val isError: Boolean = false
)

data class SessionItem(
    val id: String,
    val title: String
)

data class AppError(
    val code: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class OpenCodeUiState(
    val appMode: AppMode = AppMode.DESKTOP_RELAY,
    val isPaired: Boolean = false,
    
    // 电脑中继模式参数
    val accountId: String = "",
    val secret: String = "",
    val relayUrl: String = "ws://10.0.2.2:8765",
    
    // 云端直连模式参数
    val cloudServerUrl: String = "https://opencode.yourdomain.com:4096",
    val cloudApiKey: String = "",
    val cloudWorkspacePath: String = "/workspace",

    val isRelayConnected: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isDesktopOnline: Boolean = false,
    val isGenerating: Boolean = false,
    val isReconnecting: Boolean = false,
    val currentSessionId: String = "default",
    val availableSessions: List<SessionItem> = listOf(SessionItem("default", "Main Workspace")),
    val messages: List<ChatMessage> = emptyList(),
    val appError: AppError? = null,
    val statusBanner: String? = null
)
