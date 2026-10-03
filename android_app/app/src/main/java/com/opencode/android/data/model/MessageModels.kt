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
    val title: String,
    val tag: String = "默认",          // 自定义标签（如 "自动化任务", "代码调试", "脚本生成"）
    val isPinned: Boolean = false,     // 会话置顶
    val isArchived: Boolean = false,   // 旧会话归档
    val updatedAt: Long = System.currentTimeMillis()
)

enum class DiffLineType {
    ADDED,
    REMOVED,
    UNCHANGED,
    HEADER
}

data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val lineNumberOld: Int? = null,
    val lineNumberNew: Int? = null
)

data class ToolApprovalRequest(
    val callId: String,
    val toolName: String,
    val filePath: String? = null,
    val summary: String? = null,
    val diffLines: List<DiffLine> = emptyList(),
    val rawContent: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    // B-5: 防重放 Nonce，由 desktop agent 下发，原样回传
    val nonce: String? = null,
    val expiresAt: Long? = null
)

data class DiagnosticsResult(
    val isChecking: Boolean = false,
    val isSuccess: Boolean = false,
    val latencyMs: Long? = null,
    val statusTitle: String = "",
    val detailMessage: String = "",
    val tunnelHint: String? = null // 针对 Cloudflare Tunnel、SakuraFrp、证书等的排查提示
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
    
    // 会话与分组管理
    // B-13: 默认空会话列表，无会话时走空状态提示，不再显示假数据
    val currentSessionId: String = "",
    val availableSessions: List<SessionItem> = emptyList(),
    val selectedTagFilter: String? = null, // null 表示查看全部，支持按标签过滤
    val availableTags: List<String> = listOf("全部", "默认", "代码调试", "自动化任务", "脚本生成"),
    val showArchivedSessions: Boolean = false,

    // 工具审批与 Diff 预览
    val pendingApproval: ToolApprovalRequest? = null,

    // 连通性测试与隧道诊断结果
    val diagnostics: DiagnosticsResult? = null,

    // 日志搜索与触摸暂停滚动
    val logSearchQuery: String = "",
    val isAutoScrollPaused: Boolean = false,

    val messages: List<ChatMessage> = emptyList(),
    val appError: AppError? = null,
    val statusBanner: String? = null
)
