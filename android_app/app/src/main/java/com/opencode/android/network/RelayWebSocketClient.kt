package com.opencode.android.network

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.opencode.android.data.model.DiffLine
import com.opencode.android.data.model.DiffLineType
import com.opencode.android.data.model.SessionItem
import com.opencode.android.data.model.ToolApprovalRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * P0-4: Relay WebSocket 连接状态机。
 * UI 必须能区分网络、鉴权、Desktop、OpenCode 的不同故障，不再用 webSocket != null 粗判。
 */
enum class RelayConnectionState {
    DISCONNECTED,    // 未连接
    CONNECTING,      // WS TCP 连接建立中
    CONNECTED,       // WS 已建立，待鉴权
    AUTHENTICATING,  // 已发送 auth 包，等待 auth_ok
    AUTHENTICATED,   // 鉴权通过，待 Desktop 上线
    DESKTOP_ONLINE,  // Desktop Agent 在线，可正常使用
    RECONNECTING,    // 断线退避等待中
    AUTH_FAILED      // 鉴权失败（Secret 错误等，不自动重连）
}

interface RelayListener {
    fun onConnected()
    fun onAuthenticated()
    fun onAuthError(error: String)
    fun onDisconnected(reason: String)
    fun onReconnecting(delayMs: Long)
    fun onDesktopStatusChanged(isOnline: Boolean)
    // P0-4: 连接状态变化（UI 据此区分网络/鉴权/Desktop 故障）
    fun onConnectionStateChanged(state: RelayConnectionState) {}
    fun onStreamStart(sessionId: String)
    fun onStreamChunk(sessionId: String, chunk: String)
    fun onStreamEnd(sessionId: String)
    fun onAppError(code: String, message: String)
    fun onError(error: String)
    fun onToolApprovalRequest(request: ToolApprovalRequest) {}
    fun onSessionsListReceived(sessions: List<SessionItem>) {}
    // v1.6 P0: AI 等待用户输入
    fun onWaitingInput(sessionId: String, prompt: String) {}

    // v1.6 P0 多设备管理
    fun onDeviceListReceived(devices: List<DeviceInfo>) {}
    fun onDeviceRevoked(deviceName: String) {}
    fun onDeviceRenamed(deviceName: String) {}
    // P2-12: 文件浏览器
    fun onFileListResult(reqId: String, path: String, entries: List<FileEntry>) {}
    fun onFileReadResult(reqId: String, path: String, content: String, truncated: Boolean) {}
    // P2-15: 连接诊断
    fun onDiagnoseResult(reqId: String, opencodeOk: Boolean, version: String, error: String) {}

    // v1.6 P1 Model/Agent 管理
    fun onConfigDataReceived(agents: List<AgentInfo>, models: List<ModelInfo>, configError: String? = null) {}

    // v1.6 P1 项目管理中心
    fun onProjectsDataReceived(projects: List<ProjectInfo>, projectsError: String? = null) {}
}

/**
 * v1.6 P1: Agent 信息
 */
data class AgentInfo(
    val id: String,
    val name: String,
    val description: String = ""
)

/**
 * v1.6 P1: Model 信息
 */
data class ModelInfo(
    val providerId: String,
    val modelId: String,
    val displayName: String = ""
)

/**
 * v1.6 P1 项目管理中心：项目信息
 */
data class ProjectInfo(
    val id: String,
    val name: String,
    val path: String = "",
    val branch: String = "",
    val isCurrent: Boolean = false
)

/**
 * v1.6 P0 多设备管理：设备信息
 */
data class DeviceInfo(
    val deviceName: String,
    val createdAt: Long = 0L,
    val isOnline: Boolean = false,
    val lastActive: Long = 0L
)

/**
 * P2-12: 文件浏览器条目
 */
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val mtime: Long = 0L
)

class RelayWebSocketClient {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var listener: RelayListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentUrl: String = ""
    private var currentAccountId: String = ""
    private var currentSecret: String = ""
    private var isExplicitDisconnect: Boolean = false

    // P0-4: 连接状态机当前状态
    var connectionState: RelayConnectionState = RelayConnectionState.DISCONNECTED
        private set

    private fun setState(state: RelayConnectionState) {
        if (connectionState == state) return
        connectionState = state
        listener?.onConnectionStateChanged(state)
    }

    /** P0-4: 是否处于可用连接（取代 webSocket != null 的粗判） */
    fun isConnected(): Boolean = when (connectionState) {
        RelayConnectionState.CONNECTED,
        RelayConnectionState.AUTHENTICATING,
        RelayConnectionState.AUTHENTICATED,
        RelayConnectionState.DESKTOP_ONLINE -> true
        else -> false
    }

    // P2-10: 指数退避重连机制
    private var backoffMs = 3000L
    private val maxBackoffMs = 60000L
    private var reconnectRunnable: Runnable? = null

    // v1.6 P0 断线恢复：relay 消息序号持久化与幂等去重
    private var seqPrefs: SharedPreferences? = null
    @Volatile private var lastRelaySeq: Long = 0L
    @Volatile private var serverSeq: Long = 0L

    fun setSeqPersistence(prefs: SharedPreferences) {
        seqPrefs = prefs
    }

    /**
     * v1.6 P0 后台保活：ViewModel 重建时重新挂载监听器，不重建连接。
     */
    fun setListener(listener: RelayListener) {
        this.listener = listener
        // 新挂载的监听器立即同步当前状态
        listener.onConnectionStateChanged(connectionState)
    }

    private fun seqKey(): String {
        // P1-7: seq 按 relay + account + device 三维隔离，避免多 Relay / 多设备序号混用
        val urlHash = currentUrl.hashCode().toString(16)
        return "last_relay_seq_${urlHash}_${currentAccountId}_${deviceUuid()}"
    }

    /**
     * P1-7: 本机稳定设备标识（首次生成后持久化），用于 seq 持久化隔离。
     * 非敏感，仅做命名空间隔离。
     */
    private fun deviceUuid(): String {
        val prefs = seqPrefs ?: return "nodevice"
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        }
        return uuid
    }

    companion object {
        private const val KEY_DEVICE_UUID = "device_uuid_v1"
    }

    private fun loadPersistedSeq() {
        lastRelaySeq = seqPrefs?.getLong(seqKey(), 0L) ?: 0L
    }

    private fun persistSeq(seq: Long) {
        if (seq > lastRelaySeq) {
            lastRelaySeq = seq
            seqPrefs?.edit()?.putLong(seqKey(), seq)?.apply()
        }
    }

    fun connect(relayUrl: String, accountId: String, secret: String, listener: RelayListener) {
        cancelPendingReconnect()
        this.currentUrl = relayUrl.trim().removeSuffix("/")
        this.currentAccountId = accountId.trim()
        this.currentSecret = secret.trim()
        this.listener = listener
        this.isExplicitDisconnect = false
        // v1.6: 恢复该房间的已确认序号
        loadPersistedSeq()

        setState(RelayConnectionState.CONNECTING)
        initiateConnection()
    }

    private fun initiateConnection() {
        val wsEndpoint = if (currentUrl.endsWith("/mobile")) {
            currentUrl
        } else {
            "$currentUrl/ws/$currentAccountId/mobile"
        }

        val request = Request.Builder()
            .url(wsEndpoint)
            .build()

        setState(RelayConnectionState.CONNECTING)
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    // 重置退避
                    backoffMs = 3000L
                    setState(RelayConnectionState.CONNECTED)
                    listener?.onConnected()

                    // P0-1: 连接建立后第一包必须发送认证帧
                    // v1.6: 携带 last_relay_seq，服务端补发断线期间的消息
                    val authPacket = JSONObject().apply {
                        put("type", "auth")
                        put("account_id", currentAccountId)
                        put("secret", currentSecret)
                        put("client_type", "mobile")
                        put("last_relay_seq", lastRelaySeq)
                    }
                    webSocket.send(authPacket.toString())
                    setState(RelayConnectionState.AUTHENTICATING)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    parseIncomingMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    listener?.onDisconnected("连接已断开: $reason ($code)")
                    if (!isExplicitDisconnect && code != 4401 && code != 4429) {
                        setState(RelayConnectionState.RECONNECTING)
                        scheduleReconnect()
                    } else if (connectionState != RelayConnectionState.AUTH_FAILED) {
                        // P0-4: 鉴权失败时保持 AUTH_FAILED，不被覆盖
                        setState(RelayConnectionState.DISCONNECTED)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    val errMsg = t.localizedMessage ?: "网络连接异常"
                    listener?.onError(errMsg)
                    listener?.onDisconnected("连接失败: $errMsg")
                    if (!isExplicitDisconnect) {
                        setState(RelayConnectionState.RECONNECTING)
                        scheduleReconnect()
                    } else if (connectionState != RelayConnectionState.AUTH_FAILED) {
                        setState(RelayConnectionState.DISCONNECTED)
                    }
                }
            }
        })
    }

    private fun scheduleReconnect() {
        cancelPendingReconnect()
        val jitter = Random.nextDouble(0.85, 1.15)
        val actualDelay = (min(maxBackoffMs.toDouble(), backoffMs * jitter)).toLong()
        backoffMs = min(maxBackoffMs, (backoffMs * 1.5).toLong())

        listener?.onReconnecting(actualDelay)

        reconnectRunnable = Runnable {
            if (!isExplicitDisconnect) {
                initiateConnection()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, actualDelay)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun parseIncomingMessage(ws: WebSocket, jsonText: String) {
        try {
            val json = JSONObject(jsonText)
            // v1.6 P0 断线恢复：幂等去重——服务端补发的消息可能与已收到的重复
            if (json.has("relay_seq")) {
                val seq = json.optLong("relay_seq", -1L)
                if (seq >= 0 && seq <= lastRelaySeq) {
                    return  // 已处理过，丢弃
                }
                if (seq > 0) persistSeq(seq)
            }
            when (val type = json.optString("type")) {
                // v1.6 P0 断线恢复：服务端告知当前序号（重连后）
                "seq_sync" -> {
                    serverSeq = json.optLong("server_seq", serverSeq)
                }
                // v1.6 P0: 缓冲已过期，明确告知需要重同步而非静默丢失
                "resync_required" -> {
                    val msg = json.optString("message", "需要重新同步会话状态")
                    listener?.onAppError("RESYNC_REQUIRED", msg)
                }
                // P0-1: 认证反馈
                "auth_ok" -> {
                    setState(RelayConnectionState.AUTHENTICATED)
                    listener?.onAuthenticated()
                }
                "auth_error" -> {
                    val msg = json.optString("message", "认证失败，请检查 Secret 是否正确")
                    isExplicitDisconnect = true
                    listener?.onAuthError(msg)
                    disconnect()
                    // P0-4: disconnect 会置 DISCONNECTED，这里明确覆盖为 AUTH_FAILED
                    setState(RelayConnectionState.AUTH_FAILED)
                }

                // P1-7: 应用层心跳
                "ping" -> {
                    val pong = JSONObject().apply {
                        put("type", "pong")
                        put("timestamp", json.optLong("timestamp", System.currentTimeMillis()))
                    }
                    ws.send(pong.toString())
                }
                "pong" -> {
                    // 服务端心跳存活回执
                }

                // 桌面状态与系统消息
                "system_status" -> {
                    val desktopOnline = json.optBoolean("desktop_online", false)
                    // P0-4: Desktop 上线/下线驱动状态机
                    if (desktopOnline) {
                        setState(RelayConnectionState.DESKTOP_ONLINE)
                    } else if (connectionState == RelayConnectionState.DESKTOP_ONLINE) {
                        setState(RelayConnectionState.AUTHENTICATED)
                    }
                    listener?.onDesktopStatusChanged(desktopOnline)
                }
                // v1.6 P0 多设备管理
                "device_list" -> {
                    val arr = json.optJSONArray("devices")
                    val devices = mutableListOf<DeviceInfo>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            devices.add(DeviceInfo(
                                deviceName = o.optString("device_name", "?"),
                                createdAt = (o.optDouble("created_at", 0.0) * 1000).toLong(),
                                isOnline = o.optBoolean("is_online", false),
                                lastActive = (o.optDouble("last_active", 0.0) * 1000).toLong()
                            ))
                        }
                    }
                    listener?.onDeviceListReceived(devices)
                }
                "device_revoked", "device_revoke_failed" -> {
                    listener?.onDeviceRevoked(json.optString("device_name", ""))
                }
                "device_renamed" -> {
                    listener?.onDeviceRenamed(json.optString("device_name", ""))
                }
                "desktop_status" -> {
                    val desktopOnline = json.optBoolean("online", false)
                    listener?.onDesktopStatusChanged(desktopOnline)
                }

                // 真实会话列表回调 (P1-1)
                "sessions_list" -> {
                    val dataArray = json.optJSONArray("data")
                    val list = mutableListOf<SessionItem>()
                    if (dataArray != null) {
                        for (i in 0 until dataArray.length()) {
                            val itemObj = dataArray.getJSONObject(i)
                            val id = itemObj.optString("id", "")
                            val title = itemObj.optString("title", itemObj.optString("name", "会话 $id"))
                            if (id.isNotEmpty()) {
                                list.add(SessionItem(id = id, title = title, tag = "默认"))
                            }
                        }
                    }
                    listener?.onSessionsListReceived(list)
                }

                // P2-12: 文件浏览器结果
                "file_list_result" -> {
                    val reqId = json.optString("req_id", "")
                    val path = json.optString("path", "")
                    val entries = mutableListOf<FileEntry>()
                    val arr = json.optJSONArray("entries")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            entries.add(
                                FileEntry(
                                    name = o.optString("name", ""),
                                    isDir = o.optBoolean("is_dir", false),
                                    size = o.optLong("size", 0L),
                                    mtime = o.optLong("mtime", 0L)
                                )
                            )
                        }
                    }
                    listener?.onFileListResult(reqId, path, entries)
                }
                "file_read_result" -> {
                    listener?.onFileReadResult(
                        json.optString("req_id", ""),
                        json.optString("path", ""),
                        json.optString("content", ""),
                        json.optBoolean("truncated", false)
                    )
                }
                // P2-15: 连接诊断结果
                "diagnose_result" -> {
                    listener?.onDiagnoseResult(
                        json.optString("req_id", ""),
                        json.optBoolean("opencode_ok", false),
                        json.optString("opencode_version", ""),
                        json.optString("opencode_error", "")
                    )
                }

                // v1.6 P1: Model/Agent 配置
                "config_data" -> {
                    val agents = mutableListOf<AgentInfo>()
                    val agentArr = json.optJSONArray("agents")
                    if (agentArr != null) {
                        for (i in 0 until agentArr.length()) {
                            val o = agentArr.optJSONObject(i) ?: continue
                            val id = o.optString("id", o.optString("name", ""))
                            if (id.isNotEmpty()) {
                                agents.add(AgentInfo(
                                    id = id,
                                    name = o.optString("name", id),
                                    description = o.optString("description", "")
                                ))
                            }
                        }
                    }
                    val models = mutableListOf<ModelInfo>()
                    val providersObj = json.optJSONObject("providers")
                    // 兼容两种格式：{providers: [...]} 或 {providerId: {...}}
                    val providersArr = providersObj?.optJSONArray("providers")
                    if (providersArr != null) {
                        for (i in 0 until providersArr.length()) {
                            val p = providersArr.optJSONObject(i) ?: continue
                            val pid = p.optString("id", "")
                            val modelsObj = p.optJSONObject("models")
                            if (modelsObj != null) {
                                val keys = modelsObj.keys()
                                while (keys.hasNext()) {
                                    val mid = keys.next()
                                    val mObj = modelsObj.optJSONObject(mid)
                                    models.add(ModelInfo(
                                        providerId = pid,
                                        modelId = mid,
                                        displayName = mObj?.optString("name", mid) ?: mid
                                    ))
                                }
                            }
                        }
                    }
                    // N-6: 取不到时带错误，App 侧显示错误而非空白列表
                    val agentsErr = json.optString("agents_error", "").ifEmpty { null }
                    val providersErr = json.optString("providers_error", "").ifEmpty { null }
                    val configErr = agentsErr ?: providersErr
                    listener?.onConfigDataReceived(agents, models, configErr)
                }

                // v1.6 P1 项目管理中心
                "projects_data" -> {
                    val projects = mutableListOf<ProjectInfo>()
                    val arr = json.optJSONArray("projects")
                    val currentObj = json.optJSONObject("current")
                    val currentId = currentObj?.optString("id", "") ?: ""
                    val vcsObj = json.optJSONObject("vcs")
                    val branch = vcsObj?.optString("branch", "") ?: ""
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("id", "")
                            if (id.isNotEmpty()) {
                                projects.add(ProjectInfo(
                                    id = id,
                                    name = o.optString("name", id),
                                    path = o.optString("path", o.optString("worktree", "")),
                                    branch = branch,
                                    isCurrent = id == currentId
                                ))
                            }
                        }
                    }
                    // N-6: 取不到时带错误，App 侧显示错误而非空白列表
                    val projectsErr = json.optString("projects_error", "").ifEmpty { null }
                        ?: json.optString("vcs_error", "").ifEmpty { null }
                    listener?.onProjectsDataReceived(projects, projectsErr)
                }

                // 流式交互
                "stream_start" -> {
                    val sessionId = json.optString("session_id", "default")
                    listener?.onStreamStart(sessionId)
                }
                "stream_chunk" -> {
                    val sessionId = json.optString("session_id", "default")
                    val chunk = json.optString("chunk", "")
                    listener?.onStreamChunk(sessionId, chunk)
                }
                "stream_end" -> {
                    val sessionId = json.optString("session_id", "default")
                    listener?.onStreamEnd(sessionId)
                }
                // v1.6 P0: AI 等待用户输入（桌面端显式上报）
                "waiting_input" -> {
                    val sessionId = json.optString("session_id", "default")
                    val prompt = json.optString("prompt", "")
                    listener?.onWaitingInput(sessionId, prompt)
                }
                "cancelled" -> {
                    val sessionId = json.optString("session_id", "default")
                    listener?.onStreamEnd(sessionId)
                }

                // 工具调用审批请求 (P0-3, B-5: 解析 nonce/expires_at)
                "tool_approval_request", "approval_request" -> {
                    val callId = json.optString("call_id", UUID.randomUUID().toString())
                    val toolName = json.optString("tool_name", "edit_file")
                    val filePath = json.optString("file_path", "")
                    val summary = json.optString("summary", "")
                    val rawContent = json.optString("raw_content", "")
                    val nonce = json.optString("nonce", "").takeIf { it.isNotEmpty() }
                    val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0 }
                    val diffArray = json.optJSONArray("diff_lines")
                    val diffLines = mutableListOf<DiffLine>()
                    if (diffArray != null) {
                        for (i in 0 until diffArray.length()) {
                            val dObj = diffArray.getJSONObject(i)
                            val typeStr = dObj.optString("type", "UNCHANGED")
                            val diffType = try {
                                DiffLineType.valueOf(typeStr.uppercase())
                            } catch (e: Exception) {
                                DiffLineType.UNCHANGED
                            }
                            val content = dObj.optString("content", "")
                            diffLines.add(DiffLine(diffType, content))
                        }
                    }
                    val req = ToolApprovalRequest(
                        callId = callId,
                        toolName = toolName,
                        filePath = filePath.ifEmpty { null },
                        summary = summary.ifEmpty { null },
                        diffLines = diffLines,
                        rawContent = rawContent.ifEmpty { null },
                        nonce = nonce,
                        expiresAt = expiresAt
                    )
                    listener?.onToolApprovalRequest(req)
                }

                // P1-4: 错误协议处理
                "error" -> {
                    val code = json.optString("code", "UNKNOWN_ERROR")
                    val message = json.optString("message", "发生未知错误")
                    listener?.onAppError(code, message)
                }
                else -> {}
            }
        } catch (e: Exception) {
            listener?.onError("数据解析错误: ${e.message}")
        }
    }

    fun sendListSessions() {
        val envelope = JSONObject().apply {
            put("action", "list_sessions")
            put("req_id", UUID.randomUUID().toString())
        }
        webSocket?.send(envelope.toString())
    }

    fun sendCreateSession(title: String) {
        val envelope = JSONObject().apply {
            put("action", "create_session")
            put("req_id", UUID.randomUUID().toString())
            put("payload", JSONObject().apply { put("title", title) })
        }
        webSocket?.send(envelope.toString())
    }

    /**
     * v1.6 P0 多设备管理：请求设备列表 / 撤销设备 / 重命名设备
     */
    fun requestDeviceList() {
        webSocket?.send(JSONObject().apply { put("type", "list_devices") }.toString())
    }

    fun revokeDevice(deviceName: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "revoke_device")
            put("device_name", deviceName)
        }.toString())
    }

    fun renameDevice(oldName: String, newName: String) {
        webSocket?.send(JSONObject().apply {
            put("type", "rename_device")
            put("old_name", oldName)
            put("new_name", newName)
        }.toString())
    }

    /**
     * P2-12: 文件浏览器——列目录 / 读文件（经 Relay 转发给桌面端 agent）
     */
    fun sendFileList(path: String): String {
        val reqId = UUID.randomUUID().toString()
        val envelope = JSONObject().apply {
            put("action", "file_list")
            put("req_id", reqId)
            put("payload", JSONObject().apply { put("path", path) })
        }
        webSocket?.send(envelope.toString())
        return reqId
    }

    fun sendFileRead(path: String): String {
        val reqId = UUID.randomUUID().toString()
        val envelope = JSONObject().apply {
            put("action", "file_read")
            put("req_id", reqId)
            put("payload", JSONObject().apply { put("path", path) })
        }
        webSocket?.send(envelope.toString())
        return reqId
    }

    /**
     * P2-15: 连接诊断——请求桌面端自检 OpenCode 服务健康度
     */
    fun sendDiagnose(): String {
        val reqId = UUID.randomUUID().toString()
        val envelope = JSONObject().apply {
            put("action", "diagnose")
            put("req_id", reqId)
        }
        webSocket?.send(envelope.toString())
        return reqId
    }

    /**
     * v1.6 P1: 请求 Model/Agent 配置（动态获取）
     */
    fun requestConfig() {
        val envelope = JSONObject().apply {
            put("action", "get_config")
            put("req_id", UUID.randomUUID().toString())
        }
        webSocket?.send(envelope.toString())
    }

    /**
     * v1.6 P1 项目管理中心：请求项目列表
     */
    fun requestProjects() {
        val envelope = JSONObject().apply {
            put("action", "get_projects")
            put("req_id", UUID.randomUUID().toString())
        }
        webSocket?.send(envelope.toString())
    }

    /**
     * v1.6 P1: 发送消息时可指定 model 与 agent
     */
    fun sendPrompt(prompt: String, sessionId: String, model: ModelInfo? = null, agent: AgentInfo? = null) {
        val payload = JSONObject().apply {
            put("prompt", prompt)
            // v1.6 P1: 透传模型与 Agent 选择
            if (model != null) {
                put("model", JSONObject().apply {
                    put("providerID", model.providerId)
                    put("modelID", model.modelId)
                })
            }
            if (agent != null) {
                put("agent", agent.id)
            }
        }
        val envelope = JSONObject().apply {
            put("action", "send_prompt")
            put("session_id", sessionId)
            put("req_id", UUID.randomUUID().toString())
            put("payload", payload)
        }
        webSocket?.send(envelope.toString())
    }

    fun sendApprovalResponse(callId: String, isApproved: Boolean, reason: String = "", nonce: String? = null) {
        val payload = JSONObject().apply {
            put("call_id", callId)
            put("approved", isApproved)
            put("reason", reason)
            // B-5: nonce 原样回传，供 agent 防重放校验
            if (!nonce.isNullOrEmpty()) put("nonce", nonce)
        }
        val envelope = JSONObject().apply {
            put("action", "tool_approval_response")
            put("req_id", UUID.randomUUID().toString())
            put("payload", payload)
        }
        webSocket?.send(envelope.toString())
    }

    fun sendCancel(sessionId: String) {
        val envelope = JSONObject().apply {
            put("action", "cancel")
            put("session_id", sessionId)
            put("req_id", UUID.randomUUID().toString())
        }
        webSocket?.send(envelope.toString())
    }

    fun disconnect() {
        isExplicitDisconnect = true
        cancelPendingReconnect()
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        // P0-4: 鉴权失败时保持 AUTH_FAILED
        if (connectionState != RelayConnectionState.AUTH_FAILED) {
            setState(RelayConnectionState.DISCONNECTED)
        }
    }
}
