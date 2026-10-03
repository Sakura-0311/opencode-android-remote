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

interface RelayListener {
    fun onConnected()
    fun onAuthenticated()
    fun onAuthError(error: String)
    fun onDisconnected(reason: String)
    fun onReconnecting(delayMs: Long)
    fun onDesktopStatusChanged(isOnline: Boolean)
    fun onStreamStart(sessionId: String)
    fun onStreamChunk(sessionId: String, chunk: String)
    fun onStreamEnd(sessionId: String)
    fun onAppError(code: String, message: String)
    fun onError(error: String)
    fun onToolApprovalRequest(request: ToolApprovalRequest) {}
    fun onSessionsListReceived(sessions: List<SessionItem>) {}
}

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
    }

    fun isConnected(): Boolean = webSocket != null

    private fun seqKey() = "last_relay_seq_$currentAccountId"

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

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    // 重置退避
                    backoffMs = 3000L
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
                        scheduleReconnect()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    val errMsg = t.localizedMessage ?: "网络连接异常"
                    listener?.onError(errMsg)
                    listener?.onDisconnected("连接失败: $errMsg")
                    if (!isExplicitDisconnect) {
                        scheduleReconnect()
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
                    listener?.onAuthenticated()
                }
                "auth_error" -> {
                    val msg = json.optString("message", "认证失败，请检查 Secret 是否正确")
                    isExplicitDisconnect = true
                    listener?.onAuthError(msg)
                    disconnect()
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
                    listener?.onDesktopStatusChanged(desktopOnline)
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

    fun sendPrompt(prompt: String, sessionId: String) {
        val payload = JSONObject().apply {
            put("prompt", prompt)
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
    }
}
