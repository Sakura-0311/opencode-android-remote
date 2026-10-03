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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeCall: Call? = null

    fun checkHealth(baseUrl: String, apiKey: String, callback: (Boolean, String) -> Unit) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val healthUrl = "$cleanUrl/health"

        val requestBuilder = Request.Builder()
            .url(healthUrl)
            .get()

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val startTime = System.currentTimeMillis()
        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 如果 /health 探测失败，尝试 GET cleanUrl 根路径
                probeRootFallback(cleanUrl, apiKey, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                val latency = System.currentTimeMillis() - startTime
                val isSuccess = response.isSuccessful
                val code = response.code
                response.close()

                mainHandler.post {
                    if (isSuccess) {
                        callback(true, "云端实例响应正常 (${latency}ms, HTTP $code)")
                    } else {
                        callback(false, "云端实例返回异常状态码: HTTP $code")
                    }
                }
            }
        })
    }

    private fun probeRootFallback(cleanUrl: String, apiKey: String, callback: (Boolean, String) -> Unit) {
        val requestBuilder = Request.Builder()
            .url(cleanUrl)
            .get()

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val startTime = System.currentTimeMillis()
        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    callback(false, "无法连接到云端服务: ${e.message ?: "网络超时或拒绝连接"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val latency = System.currentTimeMillis() - startTime
                val isSuccess = response.code < 500 // 只要不是服务端崩溃或穿透502/521，说明服务在运行
                val code = response.code
                response.close()

                mainHandler.post {
                    if (isSuccess) {
                        callback(true, "云端服务可访问 (${latency}ms, HTTP $code)")
                    } else {
                        callback(false, "云端返回服务错误: HTTP $code")
                    }
                }
            }
        })
    }

    fun sendPromptStream(
        baseUrl: String,
        apiKey: String,
        sessionId: String,
        prompt: String,
        listener: CloudStreamListener
    ) {
        cancelCurrentStream()

        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val streamUrl = "$cleanUrl/api/chat"

        val jsonBody = JSONObject().apply {
            put("session_id", sessionId)
            put("prompt", prompt)
            put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(streamUrl)
            .post(requestBody)
            .header("Accept", "text/event-stream, application/json, text/plain")

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                mainHandler.post {
                    listener.onError("STREAM_CONN_ERR", "云端流连接中断: ${e.message ?: "未知网络错误"}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val code = response.code
                    val errorBody = response.body?.string() ?: ""
                    response.close()
                    mainHandler.post {
                        listener.onError("HTTP_$code", "云端请求失败 ($code): $errorBody")
                    }
                    return
                }

                mainHandler.post {
                    listener.onStreamStart(sessionId)
                }

                val responseBody = response.body
                if (responseBody == null) {
                    mainHandler.post {
                        listener.onStreamEnd(sessionId)
                    }
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (call.isCanceled()) break
                        val currentLine = line ?: continue
                        if (currentLine.isBlank()) continue

                        val chunkContent = if (currentLine.startsWith("data:")) {
                            val dataStr = currentLine.removePrefix("data:").trim()
                            if (dataStr == "[DONE]") {
                                break
                            }
                            try {
                                val json = JSONObject(dataStr)
                                json.optString("content", json.optString("text", dataStr))
                            } catch (e: Exception) {
                                dataStr
                            }
                        } else {
                            currentLine + "\n"
                        }

                        if (chunkContent.isNotEmpty()) {
                            mainHandler.post {
                                listener.onStreamChunk(sessionId, chunkContent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        mainHandler.post {
                            listener.onError("READ_ERROR", "读取流式内容异常: ${e.message}")
                        }
                    }
                } finally {
                    response.close()
                    mainHandler.post {
                        listener.onStreamEnd(sessionId)
                    }
                }
            }
        })
    }

    fun cancelCurrentStream() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
