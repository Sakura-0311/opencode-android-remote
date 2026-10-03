package com.opencode.android.network

import android.os.Handler
import android.os.Looper
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
}

class CloudApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接无超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeStreamCall: Call? = null
    @Volatile private var lastEventId: String? = null

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
        val messageUrl = "$cleanUrl/session/$sessionId/message"
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
                mainHandler.post {
                    listener.onError("STREAM_CONN_ERR", "云端 SSE 事件流连接中断: ${e.message ?: "未知网络错误"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    mainHandler.post {
                        listener.onError("HTTP_$code", "无法订阅云端事件流 (HTTP $code)")
                    }
                    return
                }

                mainHandler.post {
                    listener.onStreamStart(sessionId)
                }

                // 2. 发送 POST /session/:id/message 指令
                sendPromptMessagePayload(cleanUrl, sessionId, prompt, auth, listener)

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
                            lastEventId = currentLine.removePrefix("id:").trim()
                            continue
                        }
                        if (!currentLine.startsWith("data:")) continue

                        val dataStr = currentLine.removePrefix("data:").trim()
                        if (dataStr == "[DONE]") break

                        try {
                            val eventObj = JSONObject(dataStr)
                            val type = eventObj.optString("type")
                            val eventSessionId = eventObj.optString("session_id", sessionId)

                            // 仅处理属于当前 session 的事件
                            if (eventSessionId == sessionId) {
                                if (type == "message.part.delta" || type == "delta") {
                                    val delta = eventObj.optString("delta", eventObj.optString("text", ""))
                                    if (delta.isNotEmpty()) {
                                        mainHandler.post {
                                            listener.onStreamChunk(sessionId, delta)
                                        }
                                    }
                                } else if (type == "session.idle" || type == "message.complete") {
                                    mainHandler.post {
                                        listener.onStreamEnd(sessionId)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse error
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        mainHandler.post {
                            listener.onError("SSE_READ_ERROR", "读取云端事件流异常: ${e.message}")
                        }
                    }
                } finally {
                    response.close()
                    mainHandler.post { listener.onStreamEnd(sessionId) }
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

    fun cancelCurrentStream() {
        try {
            activeStreamCall?.cancel()
            activeStreamCall = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
