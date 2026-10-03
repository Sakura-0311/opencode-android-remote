package com.opencode.android.network

import com.opencode.android.data.model.AppMode

/**
 * v3.0: 网络传输抽象。
 *
 * 覆盖核心消息路径的 connect / send / close：
 * - RelayTransport: 电脑中继（WebSocket）
 * - CloudTransport: 云端直连（HTTPS + SSE）
 *
 * 专用能力（设备管理、文件浏览、审批响应等）仍走具体 client，
 * Transport 只抽象"连接生命周期 + 发消息"。
 */

data class TransportParams(
    val relayUrl: String = "",
    val accountId: String = "",
    val secret: String = "",
    val cloudUrl: String = "",
    val apiKey: String = "",
    val workspace: String = ""
)

data class TransportMessage(
    val text: String,
    val sessionId: String = "",
    val model: ModelInfo? = null,
    val agent: AgentInfo? = null
)

interface TransportListener {
    fun onTransportStateChanged(connected: Boolean, detail: String)
    fun onTransportError(code: String, message: String)
}

interface Transport {
    val name: String
    fun connect(params: TransportParams, listener: TransportListener)
    /** 发消息：relay 下走 send_prompt；cloud 下发起一次流式 prompt */
    fun send(message: TransportMessage): Boolean
    fun close()
    fun isConnected(): Boolean
}

/** v3.0: 按 appMode 选择传输实现（transport 切换的唯一决策点，可单测） */
object TransportFactory {
    fun select(appMode: AppMode, relay: Transport, cloud: Transport): Transport =
        if (appMode == AppMode.DESKTOP_RELAY) relay else cloud
}

/** 电脑中继传输：包装既有 RelayWebSocketClient，业务回调仍走原 RelayListener */
class RelayTransport(
    private val client: RelayWebSocketClient,
    private val relayListener: RelayListener
) : Transport {
    override val name: String = "relay"
    private var tListener: TransportListener? = null

    private val bridge = object : RelayListener by relayListener {
        override fun onConnectionStateChanged(state: RelayConnectionState) {
            relayListener.onConnectionStateChanged(state)
            tListener?.onTransportStateChanged(client.isConnected(), "relay:${state.name}")
        }

        override fun onError(error: String) {
            relayListener.onError(error)
            tListener?.onTransportError("RELAY_ERROR", error)
        }
    }

    override fun connect(params: TransportParams, listener: TransportListener) {
        tListener = listener
        client.connect(params.relayUrl, params.accountId, params.secret, bridge)
    }

    override fun send(message: TransportMessage): Boolean {
        if (!client.isConnected()) return false
        client.sendPrompt(message.text, message.sessionId, model = message.model, agent = message.agent)
        return true
    }

    override fun close() {
        tListener = null
        client.disconnect()
    }

    override fun isConnected(): Boolean = client.isConnected()
}

/** 云端直连传输：connect 做健康检查，send 发起流式 prompt */
class CloudTransport(
    private val client: CloudApiClient,
    private val streamListener: CloudStreamListener
) : Transport {
    override val name: String = "cloud"
    private var params: TransportParams? = null
    private var tListener: TransportListener? = null
    @Volatile private var healthy: Boolean = false

    override fun connect(params: TransportParams, listener: TransportListener) {
        this.params = params
        tListener = listener
        client.checkHealth(params.cloudUrl, params.apiKey) { ok, msg ->
            healthy = ok
            tListener?.onTransportStateChanged(ok, msg)
            if (!ok) tListener?.onTransportError("CLOUD_UNREACHABLE", msg)
        }
    }

    override fun send(message: TransportMessage): Boolean {
        val p = params ?: return false
        client.sendPromptStream(p.cloudUrl, p.apiKey, message.sessionId, message.text, streamListener)
        return true
    }

    override fun close() {
        tListener = null
        healthy = false
        client.cancelCurrentStream()
    }

    override fun isConnected(): Boolean = healthy
}
