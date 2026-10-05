package com.opencode.android.network

import com.opencode.android.R
import android.content.SharedPreferences
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencode.android.data.model.DiffLine
import com.opencode.android.data.model.DiffLineType
import com.opencode.android.data.model.SessionItem
import com.opencode.android.data.model.ToolApprovalRequest
import com.opencode.android.util.AppLog
import com.opencode.android.util.TAG_DEFAULT
import com.opencode.android.security.E2eeManager
import com.opencode.android.util.FeatureFlags
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

/**
 * v2.5: 云端直连（SSE）连接状态，与 RelayConnectionState 并列，
 * 顶部状态条按当前 appMode 二选一订阅显示。
 */
enum class CloudConnectionState {
    DISCONNECTED,  // 未连接
    CONNECTING,    // 正在发起流式请求
    STREAMING,     // 流式输出进行中
    RECONNECTING   // SSE 断线退避等待中
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
    // v2.3: 重同步语义——不再走 onAppError（不弹任务失败通知、不中止进度）
    fun onResyncRequired(message: String) {}
    // v2.3: 写操作未确认（socket 不可用，发送失败），由用户手动重试，不自动重发
    fun onWriteUnconfirmed(action: String, clientMsgId: String) {}
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
    fun onProjectsDataReceived(projects: List<ProjectInfo>, projectsError: String? = null) {}

    // v3.1: 多 desktop 定向路由
    fun onDesktopList(desktops: List<DesktopInfo>) {}
    fun onTargetDesktopOffline(targetDeviceId: String, message: String) {}
    // v4.0: 检测到 v3 旧服务端（hello_ack v<4），功能可用但建议升级
    fun onOldRelayVersionDetected() {}
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
    // v2.4: 设备以 deviceId 标识，deviceName 仅展示
    val deviceId: String = "",
    val deviceName: String,
    val createdAt: Long = 0L,
    val isOnline: Boolean = false,
    val lastActive: Long = 0L
)

/**
 * v3.1 多 desktop 定向路由：在线 desktop 信息（relay `desktop_list` 下发）
 */
data class DesktopInfo(
    val deviceId: String,
    val deviceName: String,
    val isPrimary: Boolean = false,
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

class RelayWebSocketClient(private val appContext: Context) {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var listener: RelayListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // 消息解析单线程执行器：OkHttp onMessage 本身是顺序回调，单线程保证解析保序；
    // 解析线程只做 JSON 解析与 E2EE 解密（不碰共享状态），序号去重与分发仍在主线程。
    private val parseExecutor = Executors.newSingleThreadExecutor()
    private val messageParser = RelayMessageParser(
        decrypt = { payload, srcId, sessId -> e2eeManager?.decryptFromDesktop(payload, srcId, sessId) }
    )

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

    // v2.3: 统一退避器（3s 起、×1.5、封顶 60s、±15% 抖动，与 v2.2 参数一致）
    private val backoff = Backoff(
        baseMs = 3000L, factor = 1.5, maxMs = 60000L,
        jitterLow = 0.85, jitterHigh = 1.15
    )
    private var reconnectRunnable: Runnable? = null
    // v2.3: socket 代号——connect() 先 cancel 旧连接，过期回调直接丢弃
    private var socketGen = 0
    // v2.3: 网络层标记离线时暂停重连计时器
    @Volatile private var networkPaused = false

    var serverProtocolVersion: Int = 0
        private set
    var serverCapabilities: List<String> = emptyList()
        private set
    // v3.0: 本次连接是否收到 hello_ack（未收到则对方是 v2 旧服务端）
    private var helloAckReceived: Boolean = false
    // v3.5: v2 旧服务端降级——跳过 hello 直接 auth（不阻断，一次性提示）
    /** 服务端是否支持某能力（v3 服务端必备；旧服务端无 hello_ack 时为空） */
    fun serverSupports(cap: String): Boolean = serverCapabilities.contains(cap)

    // v1.6 P0 断线恢复：relay 消息序号持久化与幂等去重（逻辑抽到 RelaySeqTracker，可单测）
    private var seqPrefs: SharedPreferences? = null
    @Volatile private var serverSeq: Long = 0L
    private val seqTracker = RelaySeqTracker(
        loadSeq = { seqPrefs?.getLong(seqKey(), 0L) ?: 0L },
        saveSeq = { s -> try { seqPrefs?.edit()?.putLong(seqKey(), s)?.apply() } catch (_: Exception) { } },
        loadEpoch = { seqPrefs?.getString(epochKey(), null) },
        saveEpoch = { e, s ->
            try { seqPrefs?.edit()?.putString(epochKey(), e)?.putLong(seqKey(), s)?.apply() } catch (_: Exception) { }
        },
    )

    fun setSeqPersistence(prefs: SharedPreferences) {
        seqPrefs = prefs
    }

    // v4.1: E2EE 管理器（ViewModel 注入；null 表示未启用）
    private var e2eeManager: E2eeManager? = null

    fun setE2eeManager(manager: E2eeManager?) {
        e2eeManager = manager
    }

    // v4.1: 缓存 desktop 列表，用于无显式 target 时解析主 desktop（E2EE 选密钥用）
    private var cachedDesktops: List<DesktopInfo> = emptyList()

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

    private fun epochKey(): String = seqKey() + "_epoch"

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

        // v3.0: 协议版本与能力协商
        const val PROTOCOL_VERSION = 4
        val CLIENT_CAPABILITIES = listOf(
            "hello", "write_idempotency", "file_sandbox",
            "device_id", "resync", "multi_profile"
        )
    }

    fun connect(relayUrl: String, accountId: String, secret: String, listener: RelayListener) {
        cancelPendingReconnect()
        // v2.3: 先关闭旧连接，避免重复调用留下旧 socket 及其回调
        try {
            webSocket?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        webSocket = null
        socketGen++ // 旧回调全部作废
        networkPaused = false
        this.currentUrl = relayUrl.trim().removeSuffix("/")
        this.currentAccountId = accountId.trim()
        this.currentSecret = secret.trim()
        this.listener = listener
        this.isExplicitDisconnect = false
        helloAckReceived = false
        serverCapabilities = emptyList()
        serverProtocolVersion = 0
        // v1.6: 恢复该房间的已确认序号
        seqTracker.reload()

        setState(RelayConnectionState.CONNECTING)
        initiateConnection()
    }

    // v2.3: 网络回调入口（由 NetworkMonitor 驱动）
    fun onNetworkLost() {
        AppLog.i("Relay", "network lost, pausing reconnect")
        networkPaused = true
        cancelPendingReconnect()
        if (connectionState == RelayConnectionState.RECONNECTING) {
            setState(RelayConnectionState.DISCONNECTED)
        }
    }

    fun onNetworkAvailable() {
        AppLog.i("Relay", "network available, rebuilding connection")
        val wasPaused = networkPaused
        networkPaused = false
        // 离线期间断开的（或从未连上），直接重建，不等 ping 超时
        if (!isExplicitDisconnect && !isConnected() &&
            connectionState != RelayConnectionState.AUTH_FAILED) {
            if (wasPaused || connectionState == RelayConnectionState.DISCONNECTED) {
                backoff.reset()
                initiateConnection()
            }
        }
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
        // v2.3: 每次建连递增代号；回调先验代号，过期直接丢弃
        val gen = ++socketGen
        fun isStale(): Boolean = gen != socketGen
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (isStale()) return@post
                    // v2.3: 连接成功，退避归零
                    backoff.reset()
                    setState(RelayConnectionState.CONNECTED)
                    listener?.onConnected()

                    // v4.0: hello 为强制。先 hello 能力协商，收到 hello_ack 后再发 auth
                    val hello = JSONObject().apply {
                        put("type", "hello")
                        put("v", PROTOCOL_VERSION)
                        // v4.1: E2EE 可用时声明 e2ee 能力
                        val caps = CLIENT_CAPABILITIES.toMutableList()
                        if (e2eeManager?.isAvailable() == true) caps.add("e2ee")
                        put("capabilities", JSONArray(caps))
                        put("device_id", deviceUuid())
                    }
                    webSocket.send(hello.toString())
                    AppLog.i("Relay", "v4.0 hello sent, waiting hello_ack")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // JSON 解析 + E2EE 解密移到解析线程，主线程只做去重与分发
                parseExecutor.execute {
                    val outcome = try {
                        messageParser.parseAndDecrypt(text)
                    } catch (e: Exception) {
                        // 解析阶段未预期异常：按外层 JSON 坏处理（分发阶段走异常兜底）
                        RelayMessageParser.Outcome.BadJson("解析异常: ${e.message}")
                    }
                    // 日志仍在解析线程打（AppLog 内部同步），文案与拆分前一致
                    when (outcome) {
                        is RelayMessageParser.Outcome.Ok ->
                            outcome.decryptedFrom?.let {
                                AppLog.d("Relay", "v4.1 E2EE: 已解密来自 $it 的消息")
                            }
                        is RelayMessageParser.Outcome.BadInnerJson ->
                            AppLog.w("Relay", "v4.1 E2EE: 解密后 JSON 解析失败: ${outcome.error}")
                        is RelayMessageParser.Outcome.DecryptFailed ->
                            AppLog.w("Relay", "v4.1 E2EE: 解密失败，丢弃该消息")
                        is RelayMessageParser.Outcome.BadJson -> { }
                    }
                    mainHandler.post {
                        if (isStale()) return@post
                        dispatchParsedMessage(webSocket, outcome)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (isStale()) return@post
                    AppLog.i("Relay", "ws closed code=$code reason=$reason")
                    // v4.0: 发了 hello 却没收到 hello_ack 就被 4401 → 对方是 v2 旧服务端
                    // （v2 首帧必须是 auth，收到 hello 直接关）。无 legacy 降级，明确报错。
                    if (code == 4401 && !helloAckReceived) {
                        listener?.onAppError(
                            "PROTOCOL_MISMATCH",
                            appContext.getString(R.string.relay_001)
                        )
                    }
                    listener?.onDisconnected(appContext.getString(R.string.relay_002, reason, code))
                    // v2.3: 不可重试错误（鉴权失败/被封禁）绝不重连
                    if (!isExplicitDisconnect && !Backoff.isNonRetryableCloseCode(code)) {
                        if (networkPaused) {
                            setState(RelayConnectionState.DISCONNECTED)
                        } else {
                            setState(RelayConnectionState.RECONNECTING)
                            scheduleReconnect()
                        }
                    } else if (connectionState != RelayConnectionState.AUTH_FAILED) {
                        // P0-4: 鉴权失败时保持 AUTH_FAILED，不被覆盖
                        setState(RelayConnectionState.DISCONNECTED)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (isStale()) return@post
                    val errMsg = t.localizedMessage ?: appContext.getString(R.string.relay_003)
                    AppLog.w("Relay", "ws failure: $errMsg")
                    listener?.onError(errMsg)
                    listener?.onDisconnected(appContext.getString(R.string.relay_004, errMsg))
                    if (!isExplicitDisconnect) {
                        if (networkPaused) {
                            setState(RelayConnectionState.DISCONNECTED)
                        } else {
                            setState(RelayConnectionState.RECONNECTING)
                            scheduleReconnect()
                        }
                    } else if (connectionState != RelayConnectionState.AUTH_FAILED) {
                        setState(RelayConnectionState.DISCONNECTED)
                    }
                }
            }
        })
    }

    private fun scheduleReconnect() {
        cancelPendingReconnect()
        // v2.3: 统一退避器（参数与 v2.2 一致：3s 起、×1.5、封顶 60s、±15% 抖动）
        val actualDelay = backoff.nextDelayMs()

        listener?.onReconnecting(actualDelay)

        reconnectRunnable = Runnable {
            if (!isExplicitDisconnect && !networkPaused) {
                initiateConnection()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, actualDelay)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * v3.0: hello_ack 之后发送认证帧（原 onOpen 内联逻辑抽出）。
     */
    private fun sendAuthPacket() {
        val ws = webSocket ?: return
        // P0-1: 认证帧；v1.6: 携带 last_relay_seq，服务端补发断线期间的消息
        val authPacket = JSONObject().apply {
            put("type", "auth")
            put("account_id", currentAccountId)
            // v2.2.1-D: secret 绝不打日志（AppLog 脱敏亦会处理）
            put("secret", currentSecret)
            put("client_type", "mobile")
            put("last_relay_seq", seqTracker.lastSeq)
        }
        ws.send(authPacket.toString())
        setState(RelayConnectionState.AUTHENTICATING)
    }

    /**
     * 分发阶段（主线程执行）：序号去重 + 按 type 分发。
     * 各失败分支行为与拆分前一致：外层 JSON 坏→异常兜底（断开重连）；
     * 解密失败→onError 提示后丢弃；内层 JSON 坏→记日志丢弃。
     */
    private fun dispatchParsedMessage(ws: WebSocket, outcome: RelayMessageParser.Outcome) {
        try {
            val json = when (outcome) {
                is RelayMessageParser.Outcome.Ok -> outcome.json
                is RelayMessageParser.Outcome.BadJson ->
                    throw IllegalArgumentException("消息 JSON 解析失败: ${outcome.error}")
                is RelayMessageParser.Outcome.DecryptFailed -> {
                    listener?.onError(appContext.getString(R.string.relay_005))
                    return
                }
                is RelayMessageParser.Outcome.BadInnerJson -> return
            }
            // v1.6 P0 断线恢复：幂等去重——服务端补发的消息可能与已收到的重复
            // v2.3: 先 track（内存），处理成功后再 flush 落盘
            var newSeqSeen = false
            if (json.has("relay_seq")) {
                val seq = json.optLong("relay_seq", -1L)
                if (seqTracker.isDuplicate(seq)) {
                    return  // 已处理过，丢弃
                }
                if (seq > 0) {
                    seqTracker.track(seq)
                    newSeqSeen = true
                }
            }
            // v3.1: 记录消息来源 desktop（暂只打日志，不展示）
            val srcId = json.optString("source_device_id", "")
            if (srcId.isNotEmpty()) {
                AppLog.d("Relay", "msg from desktop $srcId type=${json.optString("type")}")
            }
            when (val type = json.optString("type")) {
                // v3.0: 能力协商应答——记录服务端版本与能力，然后发 auth
                "hello_ack" -> {
                    helloAckReceived = true
                    serverProtocolVersion = json.optInt("v", 0)
                    serverCapabilities = json.optJSONArray("server_capabilities")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                        ?: emptyList()
                    AppLog.i("Relay", "hello_ack v=$serverProtocolVersion caps=$serverCapabilities")
                    // v4.0: v4 App 连 v3 relay 功能可用，但明确提示升级（每个 relayUrl 一次）
                    if (serverProtocolVersion in 1..3) {
                        try { listener?.onOldRelayVersionDetected() } catch (_: Exception) { }
                    }
                    sendAuthPacket()
                    return
                }
                // v4.0: v4 relay 对无 hello 连接的拒绝帧（v4 App 恒发 hello，正常不会收到）
                "hello_required" -> {
                    listener?.onAppError(
                        "PROTOCOL_MISMATCH",
                        appContext.getString(R.string.relay_006, json.optString("message"))
                    )
                    return
                }
                // v1.6 P0 断线恢复：服务端告知当前序号（重连后）
                "seq_sync" -> {
                    serverSeq = json.optLong("server_seq", serverSeq)
                    // v2.2.1-C: epoch 变化说明房间重建，seq 归零并提示重同步
                    // v2.3: 走 onResyncRequired
                    if (seqTracker.checkEpoch(json.optString("room_epoch", ""))) {
                        AppLog.i("Relay", "room_epoch changed, seq reset")
                        listener?.onResyncRequired(appContext.getString(R.string.relay_007))
                    }
                }
                // v1.6 P0: 缓冲已过期，明确告知需要重同步而非静默丢失
                // v2.3: 走 onResyncRequired，不再按任务失败处理
                "resync_required" -> {
                    val msg = json.optString("message", appContext.getString(R.string.relay_008))
                    AppLog.i("Relay", "resync_required: $msg")
                    listener?.onResyncRequired(msg)
                }
                // P0-1: 认证反馈
                "auth_ok" -> {
                    setState(RelayConnectionState.AUTHENTICATED)
                    // v2.2.1-C: auth_ok 也可能携带 epoch，先做检查（seq_sync 还会再确认）
                    // v2.3: 走 onResyncRequired
                    if (seqTracker.checkEpoch(json.optString("room_epoch", ""))) {
                        AppLog.i("Relay", "room_epoch changed on auth_ok, seq reset")
                        listener?.onResyncRequired(appContext.getString(R.string.relay_007))
                    }
                    listener?.onAuthenticated()
                }
                "auth_error" -> {
                    val msg = json.optString("message", appContext.getString(R.string.relay_009))
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
                                deviceId = o.optString("device_id", ""),
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
                            val title = itemObj.optString("title", itemObj.optString("name", appContext.getString(R.string.relay_010, id)))
                            if (id.isNotEmpty()) {
                                list.add(SessionItem(id = id, title = title, tag = TAG_DEFAULT))
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
                    val message = json.optString("message", appContext.getString(R.string.relay_012))
                    // v3.1: 目标电脑离线 → 针对性提示，而非笼统错误
                    val targetId = json.optString("target_device_id", "")
                    if (code == "DESKTOP_OFFLINE" && targetId.isNotEmpty()) {
                        listener?.onTargetDesktopOffline(targetId, message)
                    } else {
                        listener?.onAppError(code, message)
                    }
                }
                // v3.1: 在线 desktop 列表
                "desktop_list" -> {
                    val arr = json.optJSONArray("desktops")
                    val list = mutableListOf<DesktopInfo>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            list.add(
                                DesktopInfo(
                                    deviceId = o.optString("device_id", ""),
                                    deviceName = o.optString("device_name", "?"),
                                    isPrimary = o.optBoolean("is_primary", false),
                                    lastActive = (o.optDouble("last_active", 0.0) * 1000).toLong()
                                )
                            )
                        }
                    }
                    cachedDesktops = list
                    listener?.onDesktopList(list)
                }
                else -> {}
            }
            // v2.3: 消息处理成功后才落盘 seq
            if (newSeqSeen) seqTracker.flush()
        } catch (e: Exception) {
            // v3.4: 未知异常兜底——记日志、状态机回 DISCONNECTED（触发重连），不向上传播崩溃。
            // 每步独立 guard，避免兜底逻辑自身抛异常。
            try { AppLog.e("RelayWS", "消息分发异常兜底: ${e.message}") } catch (_: Exception) { }
            try {
                if (connectionState != RelayConnectionState.DISCONNECTED &&
                    connectionState != RelayConnectionState.AUTH_FAILED
                ) {
                    setState(RelayConnectionState.DISCONNECTED)
                }
            } catch (_: Exception) { }
            try { listener?.onError(appContext.getString(R.string.relay_013, e.message)) } catch (_: Exception) { }
        }
    }

    fun sendListSessions() {
        webSocket?.send(RelayMessageFactory.listSessions().toString())
    }

    fun sendCreateSession(title: String) {
        webSocket?.send(RelayMessageFactory.createSession(title).toString())
    }

    /**
     * v1.6 P0 多设备管理：请求设备列表 / 撤销设备 / 重命名设备
     */
    fun requestDeviceList() {
        webSocket?.send(RelayMessageFactory.listDevices().toString())
    }

    /** v3.1: 请求在线 desktop 列表（定向路由的目标选择用） */
    fun requestDesktopList() {
        webSocket?.send(RelayMessageFactory.listDesktops().toString())
    }

    // v2.4: 按 deviceId 撤销（deviceName 仅兼容旧 relay）
    fun revokeDevice(deviceId: String, deviceName: String = "") {
        webSocket?.send(RelayMessageFactory.revokeDevice(deviceId, deviceName).toString())
    }

    // v2.4: 按 deviceId 重命名
    fun renameDeviceById(deviceId: String, oldName: String, newName: String) {
        webSocket?.send(RelayMessageFactory.renameDeviceById(deviceId, oldName, newName).toString())
    }

    fun renameDevice(oldName: String, newName: String) {
        webSocket?.send(RelayMessageFactory.renameDevice(oldName, newName).toString())
    }

    /**
     * P2-12: 文件浏览器——列目录 / 读文件（经 Relay 转发给桌面端 agent）
     */
    fun sendFileList(path: String): String {
        val reqId = UUID.randomUUID().toString()
        webSocket?.send(RelayMessageFactory.fileList(path, reqId).toString())
        return reqId
    }

    fun sendFileRead(path: String): String {
        val reqId = UUID.randomUUID().toString()
        webSocket?.send(RelayMessageFactory.fileRead(path, reqId).toString())
        return reqId
    }

    /**
     * P2-15: 连接诊断——请求桌面端自检 OpenCode 服务健康度
     */
    fun sendDiagnose(): String {
        val reqId = UUID.randomUUID().toString()
        webSocket?.send(RelayMessageFactory.diagnose(reqId).toString())
        return reqId
    }

    /**
     * v1.6 P1: 请求 Model/Agent 配置（动态获取）
     */
    fun requestConfig() {
        webSocket?.send(RelayMessageFactory.getConfig().toString())
    }

    /**
     * v1.6 P1 项目管理中心：请求项目列表
     */
    fun requestProjects() {
        webSocket?.send(RelayMessageFactory.getProjects().toString())
    }

    /**
     * v1.6 P1: 发送消息时可指定 model 与 agent
     */
    // v2.3: 写操作幂等 ID（agent 侧去重，TTL 缓存）
    private fun newClientMsgId(): String = UUID.randomUUID().toString()

    /** 发送信封；返回 false 表示 socket 不可用（未确认），由调用方处理 */
    private fun sendEnvelope(action: String, envelope: JSONObject, clientMsgId: String): Boolean {
        val ok = try {
            webSocket?.send(envelope.toString()) ?: false
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            AppLog.w("Relay", "write unconfirmed: action=$action")
            listener?.onWriteUnconfirmed(action, clientMsgId)
        }
        return ok
    }

    fun sendPrompt(
        prompt: String,
        sessionId: String,
        model: ModelInfo? = null,
        agent: AgentInfo? = null,
        // v3.1: 定向路由目标 desktop（可选；为空则服务端走主 desktop）
        targetDeviceId: String? = null
    ) {
        // v3.4: 空值防御——空 prompt/空 sessionId 直接丢弃，不组装发送
        if (prompt.isBlank() || sessionId.isBlank()) {
            AppLog.w("RelayWS", "sendPrompt 丢弃空消息 promptBlank=${prompt.isBlank()} sessionBlank=${sessionId.isBlank()}")
            return
        }
        val clientMsgId = newClientMsgId()
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
        // v4.3 M-1: E2EE fail-closed——加密失败拒绝发送，绝不回退明文
        // v4.6.0: 内层格式 v2——加密 {"action","payload","seq"} JSON，AAD sender 用
        // 本机 relay device_id（desktop 侧以同一 id 存对端公钥，两端一致）
        val effectiveTarget = targetDeviceId?.ifEmpty { null }
            ?: cachedDesktops.firstOrNull { it.isPrimary }?.deviceId?.ifEmpty { null }
        val payloadResult: E2eeManager.PayloadResult? = if (!effectiveTarget.isNullOrEmpty()) {
            val inner = JSONObject().apply {
                put("action", "send_prompt")
                put("payload", payload)
            }
            e2eeManager?.encryptInnerForDesktop(inner, effectiveTarget, sessionId)
        } else null
        if (payloadResult is E2eeManager.PayloadResult.Failed) {
            AppLog.e("E2EE", "发送中止：${payloadResult.reason}")
            listener?.onError("E2EE 加密失败，已拒绝发送：${payloadResult.reason}")
            return
        }
        val envelope = JSONObject().apply {
            put("action", "send_prompt")
            put("session_id", sessionId)
            put("req_id", UUID.randomUUID().toString())
            put("client_msg_id", clientMsgId)
            // v3.1: 顶层定向字段，与 action/session_id 同级（旧 relay/agent 忽略未知字段）
            if (!targetDeviceId.isNullOrEmpty()) put("target_device_id", targetDeviceId)
            val enc = payloadResult as? E2eeManager.PayloadResult.Encrypted
            if (enc != null) {
                put("e2ee", true)
                put("encrypted_payload", enc.b64)
            } else {
                put("payload", payload)
            }
        }
        if (payloadResult is E2eeManager.PayloadResult.Encrypted) {
            AppLog.i("Relay", "v4.1 E2EE: send_prompt 已加密 -> $effectiveTarget")
        }
        sendEnvelope("send_prompt", envelope, clientMsgId)
    }

    fun sendApprovalResponse(callId: String, isApproved: Boolean, reason: String = "", nonce: String? = null) {
        webSocket?.send(RelayMessageFactory.approvalResponse(callId, isApproved, reason, nonce).toString())
    }

    fun sendCancel(sessionId: String) {
        val clientMsgId = newClientMsgId()
        sendEnvelope("cancel", RelayMessageFactory.cancel(sessionId, clientMsgId), clientMsgId)
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
