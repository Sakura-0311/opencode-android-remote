package com.opencode.android.network

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
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
        .readTimeout(0, TimeUnit.MILLISECONDS) // 流式长读取不设读取超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeCall: Call? = null

    fun checkHealth(
        baseUrl: String,
        apiKey: String,
        onResult: (isSuccess: Boolean, message: String) -> Unit
    ) {
        val url = "${baseUrl.trim().removeSuffix("/")}/health"
        val requestBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            requestBuilder.addHeader("x-api-key", apiKey.trim())
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    onResult(false, "无法连接到云端服务: ${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccess = response.isSuccessful
                val bodyStr = response.body?.string() ?: ""
                mainHandler.post {
                    if (isSuccess) {
                        onResult(true, "云端 OpenCode 服务正常就绪")
                    } else {
                        onResult(false, "云端服务返回 HTTP ${response.code}: $bodyStr")
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

        val url = "${baseUrl.trim().removeSuffix("/")}/sessions/$sessionId/message"
        val payload = JSONObject().apply {
            put("text", prompt)
            put("prompt", prompt)
        }

        val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            requestBuilder.addHeader("x-api-key", apiKey.trim())
        }

        val call = client.newCall(requestBuilder.build())
        activeCall = call

        mainHandler.post {
            listener.onStreamStart(sessionId)
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    mainHandler.post {
                        listener.onStreamEnd(sessionId)
                    }
                    return
                }
                mainHandler.post {
                    listener.onError("CLOUD_NETWORK_ERROR", e.localizedMessage ?: "云端请求失败")
                    listener.onStreamEnd(sessionId)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errText = response.body?.string() ?: "HTTP ${response.code}"
                    mainHandler.post {
                        listener.onError("CLOUD_API_ERROR", "云端返回错误 (${response.code}): $errText")
                        listener.onStreamEnd(sessionId)
                    }
                    return
                }

                val bodyStream = response.body?.byteStream()
                if (bodyStream == null) {
                    mainHandler.post {
                        listener.onError("CLOUD_EMPTY_RESPONSE", "云端返回空响应内容")
                        listener.onStreamEnd(sessionId)
                    }
                    return
                }

                try {
                    val reader = BufferedReader(bodyStream.reader())
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line?.trim() ?: continue
                        if (currentLine.isEmpty()) continue

                        val chunkText = if (currentLine.startsWith("data:")) {
                            val data = currentLine.substring(5).trim()
                            if (data == "[DONE]") break
                            try {
                                val json = JSONObject(data)
                                json.optString("text", json.optString("content", json.optString("delta", data)))
                            } catch (e: Exception) {
                                data
                            }
                        } else {
                            currentLine
                        }

                        mainHandler.post {
                            listener.onStreamChunk(sessionId, chunkText)
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        mainHandler.post {
                            listener.onError("STREAM_READ_ERROR", "读取流式数据中断: ${e.message}")
                        }
                    }
                } finally {
                    mainHandler.post {
                        listener.onStreamEnd(sessionId)
                    }
                }
            }
        })
    }

    fun cancelCurrentStream() {
        activeCall?.cancel()
        activeCall = null
    }
}
