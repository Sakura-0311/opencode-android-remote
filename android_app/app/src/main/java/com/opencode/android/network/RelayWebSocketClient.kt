package com.opencode.android.network

import android.os.Handler
import android.os.Looper
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

    fun connect(relayUrl: String, accountId: String, secret: String, listener: RelayListener) {
        cancelPendingReconnect()
        this.currentUrl = relayUrl.trim().removeSuffix("/")
        this.currentAccountId = accountId.trim()
        this.currentSecret = secret.trim()
        this.listener = listener
        this.isExplicitDisconnect = false

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
                    val authPacket = JSONObject().apply {
                        put("type", "auth")
                        put("account_id", currentAccountId)
                        put("secret", currentSecret)
                        put("client_type", "mobile")
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
            when (val type = json.optString("type")) {
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

                // P1-4: 错误协议处理
                "error" -> {
                    val code = json.optString("code", "UNKNOWN_ERROR")
                    val message = json.optString("message", "发生未知错误")
                    listener?.onAppError(code, message)
                }
                else -> {
                    // 兼容旧格式或扩展字段
                }
            }
        } catch (e: Exception) {
            listener?.onError("数据解析错误: ${e.message}")
        }
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
