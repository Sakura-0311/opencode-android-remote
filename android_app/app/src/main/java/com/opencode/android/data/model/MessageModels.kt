package com.opencode.android.data.model

import com.opencode.android.util.TAG_ALL
import com.opencode.android.util.TAG_AUTO
import com.opencode.android.util.TAG_DEBUG
import com.opencode.android.util.TAG_DEFAULT
import com.opencode.android.util.TAG_SCRIPT

import com.opencode.android.R
import androidx.annotation.StringRes

import com.opencode.android.network.AgentInfo
import com.opencode.android.network.DesktopInfo
import com.opencode.android.network.DeviceInfo
import com.opencode.android.network.FileEntry
import com.opencode.android.network.ModelInfo
import com.opencode.android.network.ProjectInfo
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.network.CloudConnectionState

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class AppMode {
    DESKTOP_RELAY, // 模式 1: 电脑远程控制 (通过 Relay 操控家中/办公室 PC)
    CLOUD_HOSTED   // 模式 2: 云端工作区直连 (无需开电脑，直接使用云端 OpenCode 实例)
}

/**
 * v1.6 P0 后台保活：任务状态机
 * 异常情况下明确显示：运行中、等待输入、权限审批、失败、已完成、已断开
 */
enum class TaskStatus(@StringRes val labelRes: Int) {
    IDLE(R.string.msg_status_idle),
    RUNNING(R.string.msg_status_running),
    WAITING_INPUT(R.string.msg_status_waiting_input),
    APPROVAL_REQUIRED(R.string.msg_status_approval),
    FAILED(R.string.msg_status_failed),
    COMPLETED(R.string.msg_status_completed),
    DISCONNECTED(R.string.msg_status_disconnected)
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
    val tag: String = TAG_DEFAULT,  // 标签 key（内置）或用户自建文案
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

    // v1.6 P0 后台保活：明确的任务状态（锁屏/后台/重连后可恢复显示）
    val taskStatus: TaskStatus = TaskStatus.IDLE,
    val taskStatusDetail: String = "",
    // P2-13: 任务中心用——当前任务开始时间戳
    val taskStartTimeMs: Long = 0L,
    // v1.6 P0 多设备管理：已配对设备列表
    val pairedDevices: List<DeviceInfo> = emptyList(),
    // v1.6 P1 Model/Agent：可用列表与当前选择
    val availableAgents: List<AgentInfo> = emptyList(),
    val availableModels: List<ModelInfo> = emptyList(),
    val selectedAgent: AgentInfo? = null,
    val selectedModel: ModelInfo? = null,
    // N-6: 元信息拉取失败时的错误（非空则对话框显示错误而非空白列表）
    val configError: String? = null,
    // v1.6 P1 项目管理中心
    val projects: List<ProjectInfo> = emptyList(),
    val favoriteProjectIds: Set<String> = emptySet(),
    val projectsError: String? = null,
    // v3.1: 多 desktop 定向路由——在线列表 / 已选目标（空=走主）/ 列表弹窗 / 离线提示 / 待确认切换
    val desktopList: List<DesktopInfo> = emptyList(),
    val targetDesktopId: String = "",
    val showDesktopList: Boolean = false,
    val targetOfflineHint: String? = null,
    val pendingTargetSwitch: String? = null,

    // v3.2: 安全存储状态（诊断页展示；迁移回退时用户可见）
    val secureStorageInfo: String = "",
    val secureStorageOk: Boolean = true,
    val showSecureMigrationNotice: Boolean = false,
    // v4.0: v3 旧服务端升级提示（hello_ack v<4 时一次性，不阻断）
    val showOldRelayWarning: Boolean = false,
    // v4.3 M-5: E2EE 状态可见——当前目标 desktop 是否已有协商公钥；配对成功一次提示
    val e2eePeerReady: Boolean = false,
    val showE2eeChannelDialog: Boolean = false,
    
    // 电脑中继模式参数
    val accountId: String = "",
    val secret: String = "",
    // 默认留空：配对页用 placeholder 给输入提示（此前默认填模拟器地址 ws://10.0.2.2:8765，
    // 真机用户容易误以为已填好）。release 环境请用 wss，见 docs/RELAY_DEPLOY.md。
    val relayUrl: String = "",
    
    // 云端直连模式参数
    val cloudServerUrl: String = "https://opencode.yourdomain.com:4096",
    val cloudApiKey: String = "",
    val cloudWorkspacePath: String = "/workspace",

    val isRelayConnected: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isDesktopOnline: Boolean = false,
    val isGenerating: Boolean = false,
    val isReconnecting: Boolean = false,
    // P0-4: Relay 连接状态机（UI 据此区分网络/鉴权/Desktop 故障）
    val relayConnectionState: RelayConnectionState = RelayConnectionState.DISCONNECTED,
    // v2.5: 云端直连状态机；顶部状态条按 appMode 二选一订阅显示
    val cloudConnectionState: CloudConnectionState = CloudConnectionState.DISCONNECTED,
    // v2.5: 多连接 profiles
    val profiles: List<ConnectionProfile> = emptyList(),
    val activeProfileId: String = "",
    // P2-12: 文件浏览器状态
    val fileBrowserPath: String = "",
    val fileBrowserEntries: List<FileEntry> = emptyList(),
    val fileBrowserLoading: Boolean = false,
    val filePreviewPath: String = "",
    val filePreviewContent: String = "",
    val filePreviewTruncated: Boolean = false,
    // P2-15: 连接诊断结果
    val diagnoseLoading: Boolean = false,
    val diagnoseOpencodeOk: Boolean? = null,
    val diagnoseOpencodeVersion: String = "",
    val diagnoseOpencodeError: String = "",
    
    // 会话与分组管理
    // B-13: 默认空会话列表，无会话时走空状态提示，不再显示假数据
    val currentSessionId: String = "",
    val availableSessions: List<SessionItem> = emptyList(),
    val selectedTagFilter: String? = null, // null 表示查看全部，支持按标签过滤
    // v4.3 M-4: 内部用稳定 key，显示时映射文案
    val availableTags: List<String> = listOf(TAG_ALL, TAG_DEFAULT, TAG_DEBUG, TAG_AUTO, TAG_SCRIPT),
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
