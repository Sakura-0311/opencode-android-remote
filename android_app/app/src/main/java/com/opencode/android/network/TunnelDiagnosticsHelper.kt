package com.opencode.android.network

import android.os.Handler
import android.content.Context
import android.os.Looper
import com.opencode.android.data.model.DiagnosticsResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

object TunnelDiagnosticsHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun diagnoseEndpoint(context: Context, targetUrl: String, apiKeyOrSecret: String, callback: (DiagnosticsResult) -> Unit) {
        val trimmedUrl = targetUrl.trim()

        if (trimmedUrl.isBlank()) {
            postResult(
                callback,
                DiagnosticsResult(
                    isChecking = false,
                    isSuccess = false,
                    statusTitle = context.getString(R.string.tunnel_001),
                    detailMessage = context.getString(R.string.tunnel_002),
                    tunnelHint = context.getString(R.string.tunnel_003)
                )
            )
            return
        }

        // 解析并转换协议为 HTTP/HTTPS 进行探活探测
        val httpUrl = convertToHttpUrl(trimmedUrl)
        if (httpUrl == null) {
            postResult(
                callback,
                DiagnosticsResult(
                    isChecking = false,
                    isSuccess = false,
                    statusTitle = context.getString(R.string.tunnel_004),
                    detailMessage = context.getString(R.string.tunnel_005),
                    tunnelHint = context.getString(R.string.tunnel_006)
                )
            )
            return
        }

        Thread {
            val startTime = System.currentTimeMillis()
            try {
                val requestBuilder = Request.Builder()
                    .url(httpUrl)
                    .header("User-Agent", "OpenCode-Android-Diagnostics/1.0")

                if (apiKeyOrSecret.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $apiKeyOrSecret")
                }

                val response: Response = probeClient.newCall(requestBuilder.build()).execute()
                val latency = System.currentTimeMillis() - startTime
                val code = response.code
                val serverHeader = response.header("Server", "").orEmpty()
                val cfRay = response.header("cf-ray", "").orEmpty()
                val isCloudflare = cfRay.isNotBlank() || serverHeader.contains("cloudflare", ignoreCase = true)
                val isSakuraFrp = isSakuraFrpDomain(httpUrl) || response.header("X-Powered-By", "").orEmpty().contains("SakuraFrp", ignoreCase = true)
                val bodySnippet = try { response.body?.string()?.take(500) ?: "" } catch (e: Exception) { "" }
                response.close()

                analyzeHttpResponse(code, latency, isCloudflare, isSakuraFrp, bodySnippet, callback)

            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                analyzeException(e, latency, trimmedUrl, isSakuraFrpDomain(httpUrl), callback)
            }
        }.start()
    }

    private fun convertToHttpUrl(url: String): String? {
        return try {
            val normalized = when {
                url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
                url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
                url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> url
                else -> "https://$url"
            }
            val uri = URI(normalized)
            if (uri.host == null) null else normalized
        } catch (e: Exception) {
            null
        }
    }

    private fun isSakuraFrpDomain(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("sakurafrp") || lower.contains("natfrp") || lower.contains("frp")
    }

    private fun analyzeHttpResponse(
        code: Int,
        latency: Long,
        isCloudflare: Boolean,
        isSakuraFrp: Boolean,
        bodySnippet: String,
        callback: (DiagnosticsResult) -> Unit
    ) {
        when {
            // Cloudflare 特征状态码
            isCloudflare && code == 521 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_007),
                        detailMessage = context.getString(R.string.tunnel_008),
                        tunnelHint = context.getString(R.string.tunnel_009)
                    )
                )
            }
            isCloudflare && code == 522 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_010),
                        detailMessage = context.getString(R.string.tunnel_011),
                        tunnelHint = context.getString(R.string.tunnel_012)
                    )
                )
            }
            isCloudflare && code == 520 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_013),
                        detailMessage = context.getString(R.string.tunnel_014),
                        tunnelHint = context.getString(R.string.tunnel_015)
                    )
                )
            }
            isCloudflare && code == 524 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_016),
                        detailMessage = context.getString(R.string.tunnel_017),
                        tunnelHint = context.getString(R.string.tunnel_018)
                    )
                )
            }
            isCloudflare && (code == 403 || code == 302) && (bodySnippet.contains("Zero Trust", ignoreCase = true) || bodySnippet.contains("cloudflareaccess")) -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_019),
                        detailMessage = context.getString(R.string.tunnel_020),
                        tunnelHint = context.getString(R.string.tunnel_021)
                    )
                )
            }
            isSakuraFrp && (code == 502 || bodySnippet.contains(context.getString(R.string.tunnel_022)) || bodySnippet.contains(context.getString(R.string.tunnel_023))) -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_024, code),
                        detailMessage = context.getString(R.string.tunnel_025),
                        tunnelHint = context.getString(R.string.tunnel_026)
                    )
                )
            }
            // 正常状态 (200..399 或 401/404 说明服务进程在正常监听)
            code in 200..399 || code == 401 || code == 404 || code == 405 -> {
                val tunnelType = when {
                    isCloudflare -> context.getString(R.string.tunnel_027)
                    isSakuraFrp -> context.getString(R.string.tunnel_028)
                    else -> context.getString(R.string.tunnel_029)
                }
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = true,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_030, tunnelType),
                        detailMessage = context.getString(R.string.tunnel_031, code, latency),
                        tunnelHint = if (isCloudflare) context.getString(R.string.tunnel_032) else null
                    )
                )
            }
            code == 502 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_033),
                        detailMessage = context.getString(R.string.tunnel_034),
                        tunnelHint = context.getString(R.string.tunnel_035)
                    )
                )
            }
            else -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_036, code),
                        detailMessage = context.getString(R.string.tunnel_037, bodySnippet.take(120)),
                        tunnelHint = context.getString(R.string.tunnel_038)
                    )
                )
            }
        }
    }

    private fun analyzeException(
        e: Exception,
        latency: Long,
        targetUrl: String,
        isSakuraFrp: Boolean,
        callback: (DiagnosticsResult) -> Unit
    ) {
        when (e) {
            is SSLHandshakeException, is SSLException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_039),
                        detailMessage = context.getString(R.string.tunnel_040, e.message ?: context.getString(R.string.tunnel_n01)),
                        tunnelHint = context.getString(R.string.tunnel_041)
                    )
                )
            }
            is UnknownHostException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_042),
                        detailMessage = context.getString(R.string.tunnel_043, e.message),
                        tunnelHint = context.getString(R.string.tunnel_044)
                    )
                )
            }
            is ConnectException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_045),
                        detailMessage = context.getString(R.string.tunnel_046),
                        tunnelHint = context.getString(R.string.tunnel_047)
                    )
                )
            }
            is SocketTimeoutException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_048),
                        detailMessage = context.getString(R.string.tunnel_049),
                        tunnelHint = context.getString(R.string.tunnel_050)
                    )
                )
            }
            else -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = context.getString(R.string.tunnel_051),
                        detailMessage = context.getString(R.string.tunnel_053, e.javaClass.simpleName, e.message ?: context.getString(R.string.tunnel_054)),
                        tunnelHint = context.getString(R.string.tunnel_052)
                    )
                )
            }
        }
    }

    private fun postResult(callback: (DiagnosticsResult) -> Unit, result: DiagnosticsResult) {
        mainHandler.post { callback(result) }
    }
}
