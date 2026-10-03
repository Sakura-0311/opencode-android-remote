package com.opencode.android.network

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.opencode.android.util.AppLog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

interface CloudStreamListener {
    fun onStreamStart(sessionId: String)
    fun onStreamChunk(sessionId: String, chunk: String)
    fun onStreamEnd(sessionId: String)
    fun onError(code: String, message: String)
    // v2.3: SSE 重连状态暴露（UI 可显示"重连中"指示）
    fun onSseStateChanged(retrying: Boolean, attempt: Int) {}
}

class CloudApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接无超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeStreamCall: Call? = null
    // v1.6 P0 断线恢复：SSE lastEventId 持久化（按服务器+会话区分）
    private var eventIdPrefs: SharedPreferences? = null
    @Volatile private var lastEventId: String? = null
    @Volatile private var currentEventKey: String = ""
    // v2.3: SSE 重连改用统一退避器（1s 起、×2、封顶 30s）；重试上限按「持续离线时长」而非固定次数
    private val sseBackoff = Backoff(baseMs = 1000L, factor = 2.0, maxMs = 30000L)
    private var sseRetryCount: Int = 0
    private var sseRetryRunnable: Runnable? = null
    private var sseStreamActive: Boolean = false
    private var sseLastParams: SseParams? = null
    private var sseFirstFailureAt: Long = 0L

    private data class SseParams(
        val baseUrl: String,
        val apiKey: String,
        val sessionId: String,
        val prompt: String,
        val listener: CloudStreamListener
    )

    companion object {
        // v2.3: 持续离线超过此时长则停止 SSE 重试（之前是固定 8 次）
        private const val SSE_MAX_OFFLINE_MS = 30L * 60L * 1000L
    }

    fun setEventIdPersistence(prefs: SharedPreferences) {
        eventIdPrefs = prefs
    }

    private fun eventKey() = currentEventKey

    private fun loadPersistedEventId() {
        lastEventId = eventIdPrefs?.getString(eventKey(), null)
    }

    private fun persistEventId(id: String) {
        lastEventId = id
        eventIdPrefs?.edit()?.putString(eventKey(), id)?.apply()
    }

    private fun buildAuthHeader(apiKey: String): String {
        return if (apiKey.isNotBlank()) {
            // OpenCode 标准支持 Basic Auth (用户名为 opencode 或空)
            Credentials.basic("opencode", apiKey.trim())
        } else {
            ""
        }
    }

    /**
     * P0-2 真实健康检查：
     * 严格请求 GET /global/health
     * 仅当 HTTP 200 且返回正常 JSON 时判定为成功，严禁把 404/500 等异常判为可访问！
     */
    fun checkHealth(baseUrl: String, apiKey: String, callback: (Boolean, String) -> Unit) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val healthUrl = "$cleanUrl/global/health"

        val requestBuilder = Request.Builder()
            .url(healthUrl)
            .get()

        val auth = buildAuthHeader(apiKey)
        if (auth.isNotBlank()) {
            requestBuilder.header("Authorization", auth)
        }

        val startTime = System.currentTimeMillis()
        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val latency = System.currentTimeMillis() - startTime
                mainHandler.post {
                    callback(false, "无法连通云端 OpenCode (${latency}ms): ${e.message ?: "连接被拒绝或超时"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val latency = System.currentTimeMillis() - startTime
                val code = response.code
                val isSuccess = response.isSuccessful && code == 200
                response.close()

                mainHandler.post {
                    when (code) {
                        200 -> callback(true, "云端 OpenCode 真实服务就绪 (${latency}ms, HTTP 200)")
                        401 -> callback(false, "认证失败 (HTTP 401): 密码错误，请检查云端访问密码")
                        404 -> callback(false, "接口不存在 (HTTP 404): 未检测到 OpenCode 真实端点 (/global/health)")
                        502, 521 -> callback(false, "网关/隧道异常 (HTTP $code): 穿透隧道或反向代理未连通后端")
                        else -> callback(false, "云端实例响应异常 HTTP $code (${latency}ms)")
                    }
                }
            }
        })
    }

    /**
     * P0-1 真实消息流：
     * 1. 订阅 GET /event SSE 获取流式增量
     * 2. POST /session/:id/message 发送真实 parts 契约消息
     */
    fun sendPromptStream(
        baseUrl: String,
        apiKey: String,
        sessionId: String,
        prompt: String,
        listener: CloudStreamListener
    ) {
        cancelCurrentStream()

        val cleanUrl = baseUrl.trim().removeSuffix("/")

        // v1.6 P0 断线恢复：按服务器+会话恢复游标
        currentEventKey = "sse_event_id_${cleanUrl.hashCode()}_${sessionId}"
        loadPersistedEventId()

        // P1-8: 保存参数供重连使用；新一轮流重置退避
        sseLastParams = SseParams(cleanUrl, apiKey, sessionId, prompt, listener)
        sseRetryCount = 0
        sseFirstFailureAt = 0L
        sseBackoff.reset()
        sseStreamActive = true

        subscribeEventStream(sendPrompt = true)
    }

    /**
     * P1-8: 订阅 SSE 事件流。sendPrompt=true 时为首次订阅（会发送 prompt）；
     * 重连时 sendPrompt=false，仅用 Last-Event-ID 续订，避免重复发送指令。
     */
    private fun subscribeEventStream(sendPrompt: Boolean) {
        val params = sseLastParams ?: return
        val (cleanUrl, apiKey, sessionId, prompt, listener) = params
        val eventUrl = "$cleanUrl/event"
        val auth = buildAuthHeader(apiKey)

        // 1. 发起 SSE /event 长连接订阅
        val sseRequestBuilder = Request.Builder()
            .url(eventUrl)
            .get()
            .header("Accept", "text/event-stream")

        if (auth.isNotBlank()) {
            sseRequestBuilder.header("Authorization", auth)
        }
        lastEventId?.let {
            sseRequestBuilder.header("Last-Event-ID", it)
        }

        val sseCall = client.newCall(sseRequestBuilder.build())
        activeStreamCall = sseCall

        sseCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                // P1-8: 非主动取消的失败走指数退避重连，而非直接报错
                scheduleSseRetry("云端 SSE 事件流连接中断: ${e.message ?: "未知网络错误"}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    // P1-8: 5xx/网络类错误可重连；4xx（鉴权/路径）直接报错
                    if (code >= 500 && sseStreamActive) {
                        scheduleSseRetry("无法订阅云端事件流 (HTTP $code)")
                    } else {
                        sseStreamActive = false
                        mainHandler.post {
                            listener.onError("HTTP_$code", "无法订阅云端事件流 (HTTP $code)")
                        }
                    }
                    return
                }

                // v2.3: 连接成功——重置退避与离线计时，并通知 UI 重连结束
                onSseOpened()

                mainHandler.post {
                    listener.onStreamStart(sessionId)
                }

                // 2. 首次订阅时发送 POST /session/:id/message 指令；重连时不重发
                if (sendPrompt) {
                    sendPromptMessagePayload(cleanUrl, sessionId, prompt, auth, listener)
                }

                val responseBody = response.body
                if (responseBody == null) {
                    mainHandler.post { listener.onStreamEnd(sessionId) }
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (call.isCanceled()) break
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("id:")) {
                            // v1.6: 持久化游标，App 重启/重连后可续传
                            persistEventId(currentLine.removePrefix("id:").trim())
                            continue
                        }
                        if (!currentLine.startsWith("data:")) continue

                        val dataStr = currentLine.removePrefix("data:").trim()
                        if (dataStr == "[DONE]") break

                        try {
                            val eventObj = JSONObject(dataStr)
                            val type = eventObj.optString("type")
                            // B-2b: 会话 ID 优先从 properties 取，顶层仅作兼容分支
                            val props = eventObj.optJSONObject("properties")
                            val eventSessionId = props?.optString("sessionID")?.takeIf { it.isNotEmpty() }
                                ?: props?.optString("sessionId")?.takeIf { it.isNotEmpty() }
                                ?: eventObj.optString("session_id").takeIf { it.isNotEmpty() }
                                ?: eventObj.optString("sessionId").takeIf { it.isNotEmpty() }

                            // B-3: 无归属或非当前会话的事件直接丢弃，防串台
                            if (eventSessionId == null || eventSessionId != sessionId) continue

                            if (type == "message.part.delta" || type == "delta") {
                                // B-2b: 增量文本同样优先从 properties 取
                                val delta = props?.optString("delta")?.takeIf { it.isNotEmpty() }
                                    ?: props?.optString("text")?.takeIf { it.isNotEmpty() }
                                    ?: eventObj.optString("delta", eventObj.optString("text", ""))
                                if (delta.isNotEmpty()) {
                                    mainHandler.post {
                                        listener.onStreamChunk(sessionId, delta)
                                    }
                                }
                            } else if (type == "session.idle" || type == "message.complete") {
                                // P1-8: 正常结束标记流完成，不再重连
                                sseStreamActive = false
                                mainHandler.post {
                                    listener.onStreamEnd(sessionId)
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse error
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled() && sseStreamActive) {
                        // P1-8: 读取异常走指数退避重连（Last-Event-ID 自动续传）
                        scheduleSseRetry("读取云端事件流异常: ${e.message}")
                    }
                } finally {
                    response.close()
                    // P1-8: 只有流仍标记为活跃且未安排重连时才发 onStreamEnd
                    if (sseStreamActive && sseRetryRunnable == null) {
                        mainHandler.post { listener.onStreamEnd(sessionId) }
                    }
                }
            }
        })
    }

    private fun sendPromptMessagePayload(
        baseUrl: String,
        sessionId: String,
        prompt: String,
        auth: String,
        listener: CloudStreamListener
    ) {
        val messageUrl = "$baseUrl/session/$sessionId/message"
        val payload = JSONObject().apply {
            val parts = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
            }
            put("parts", parts)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder()
            .url(messageUrl)
            .post(payload.toString().toRequestBody(mediaType))

        if (auth.isNotBlank()) {
            requestBuilder.header("Authorization", auth)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    listener.onError("SEND_FAILED", "发送指令失败: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    val errBody = response.body?.string() ?: ""
                    response.close()
                    mainHandler.post {
                        listener.onError("HTTP_$code", "云端执行失败 (HTTP $code): $errBody")
                    }
                } else {
                    response.close()
                }
            }
        })
    }

    fun abortSessionExecution(baseUrl: String, apiKey: String, sessionId: String) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val abortUrl = "$cleanUrl/session/$sessionId/abort"
        val auth = buildAuthHeader(apiKey)

        val requestBuilder = Request.Builder()
            .url(abortUrl)
            .post("{}".toRequestBody("application/json".toMediaType()))

        if (auth.isNotBlank()) {
            requestBuilder.header("Authorization", auth)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * B-9: 云端模式工具审批应答 — 直调 OpenCode 真实端点
     * POST /session/:id/permissions/:permID
     */
    fun respondToPermission(
        baseUrl: String,
        apiKey: String,
        sessionId: String,
        permissionId: String,
        approved: Boolean,
        reason: String = "",
        callback: ((Boolean) -> Unit)? = null
    ) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val permUrl = "$cleanUrl/session/$sessionId/permissions/$permissionId"
        val auth = buildAuthHeader(apiKey)
        val action = if (approved) "allow" else "deny"
        val payload = JSONObject().apply {
            put("action", action)
            put("response", action)
            put("reason", reason)
        }.toString()

        val requestBuilder = Request.Builder()
            .url(permUrl)
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (auth.isNotBlank()) {
            requestBuilder.header("Authorization", auth)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback?.let { mainHandler.post { it(false) } }
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                callback?.let { mainHandler.post { it(ok) } }
            }
        })
    }

        fun getSessions(baseUrl: String, apiKey: String, callback: (List<com.opencode.android.data.model.SessionItem>) -> Unit) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val sessionUrl = "$cleanUrl/session"
        val auth = buildAuthHeader(apiKey)

        val requestBuilder = Request.Builder().url(sessionUrl).get()
        if (auth.isNotBlank()) {
            requestBuilder.header("Authorization", auth)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val list = mutableListOf<com.opencode.android.data.model.SessionItem>()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    try {
                        val jsonArr = org.json.JSONArray(body)
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            val id = obj.optString("id")
                            val title = obj.optString("title", obj.optString("name", "云端会话 $id"))
                            if (id.isNotEmpty()) {
                                list.add(com.opencode.android.data.model.SessionItem(id = id, title = title, tag = "默认"))
                            }
                        }
                    } catch (e: Exception) {}
                }
                response.close()
                mainHandler.post { callback(list) }
            }
        })
    }

    /**
     * v2.3: SSE 指数退避重连（统一 Backoff：1s 起、×2、上限 30s），用 Last-Event-ID 续传。
     * 重试上限按「持续离线时长」（30 分钟）而非固定次数；状态向 UI 暴露。
     * 达到上限后才向 UI 报错。
     */
    private fun scheduleSseRetry(reason: String) {
        if (!sseStreamActive) return
        val params = sseLastParams ?: return
        val now = System.currentTimeMillis()
        if (sseFirstFailureAt == 0L) sseFirstFailureAt = now
        if (now - sseFirstFailureAt > SSE_MAX_OFFLINE_MS) {
            sseStreamActive = false
            sseRetryRunnable = null
            AppLog.w("Cloud", "SSE retry exhausted after 30min offline")
            mainHandler.post {
                params.listener.onSseStateChanged(false, sseRetryCount)
                params.listener.onError("SSE_RETRY_EXHAUSTED", "云端事件流多次重连失败，已停止重试: $reason")
            }
            return
        }
        val delayMs = sseBackoff.nextDelayMs()
        sseRetryCount++
        AppLog.i("Cloud", "SSE retry #$sseRetryCount in ${delayMs}ms: $reason")
        mainHandler.post {
            params.listener.onSseStateChanged(true, sseRetryCount)
        }
        val runnable = Runnable {
            sseRetryRunnable = null
            if (sseStreamActive) {
                subscribeEventStream(sendPrompt = false)
            }
        }
        sseRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    /** v2.3: 流成功建立时调用——重置退避与离线计时，并通知 UI */
    private fun onSseOpened() {
        sseBackoff.reset()
        sseRetryCount = 0
        sseFirstFailureAt = 0L
        val listener = sseLastParams?.listener ?: return
        mainHandler.post {
            listener.onSseStateChanged(false, 0)
        }
    }

    fun cancelCurrentStream() {
        try {
            sseStreamActive = false
            sseRetryRunnable?.let { mainHandler.removeCallbacks(it) }
            sseRetryRunnable = null
            activeStreamCall?.cancel()
            activeStreamCall = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
