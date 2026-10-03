package com.opencode.android.network

import android.os.Handler
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

    fun diagnoseEndpoint(targetUrl: String, apiKeyOrSecret: String, callback: (DiagnosticsResult) -> Unit) {
        val trimmedUrl = targetUrl.trim()

        if (trimmedUrl.isBlank()) {
            postResult(
                callback,
                DiagnosticsResult(
                    isChecking = false,
                    isSuccess = false,
                    statusTitle = "地址为空",
                    detailMessage = "请先填写服务器或中继服务地址。",
                    tunnelHint = "提示：电脑中继模式填写中继服务器地址 (如 ws://10.0.2.2:8765 或 wss://relay.example.com)，云端模式填写 VPS 实例地址。"
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
                    statusTitle = "URL 格式不合法",
                    detailMessage = "无法解析该地址，请确保格式正确。",
                    tunnelHint = "标准格式示例：\n- 中继服务: wss://your-relay.example.com\n- 局域网/测试: ws://192.168.1.100:8765 或 ws://10.0.2.2:8765\n- 云端 VPS: https://opencode.yourdomain.com:4096"
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
                        statusTitle = "Cloudflare 521: 源站服务未启动",
                        detailMessage = "Cloudflare Tunnel 隧道已连接，但无法连通 VPS 本地的 OpenCode 服务。",
                        tunnelHint = "【Cloudflare Tunnel 排查建议】\n1. 登录 VPS 检查 OpenCode 进程是否运行 (如: `pgrep opencode` 或 `opencode serve`);\n2. 检查 cloudflared 配置文件中的 service 端口是否与 OpenCode 端口 (默认 4096) 严格一致；\n3. 确认 VPS 本地没有防火墙阻断 127.0.0.1 端口。"
                    )
                )
            }
            isCloudflare && code == 522 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "Cloudflare 522: 连接源站超时",
                        detailMessage = "Cloudflare 与源站的 TCP 握手超时，隧道链路不稳定或源站无响应。",
                        tunnelHint = "【Cloudflare Tunnel 排查建议】\n1. 检查 VPS 的 cloudflared 守护进程状态 (`systemctl status cloudflared`)；\n2. 检查 VPS 安全组与出口带宽是否跑满。"
                    )
                )
            }
            isCloudflare && code == 520 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "Cloudflare 520: 源站异常重置",
                        detailMessage = "源站向 Cloudflare 返回了空响应或非标准响应。",
                        tunnelHint = "【排查建议】\n检查 VPS 上的 Nginx / Caddy 反向代理日志，确认是否配置了正确的反代协议 (HTTP/WebSocket)。"
                    )
                )
            }
            isCloudflare && code == 524 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "Cloudflare 524: 响应超时",
                        detailMessage = "OpenCode 处理请求耗时超过 Cloudflare 100 秒网关限制。",
                        tunnelHint = "【排查建议】\n长任务建议开启流式模式 (Stream) 输出，保持 HTTP 数据帧活跃以避免 100 秒静默超时。"
                    )
                )
            }
            isCloudflare && (code == 403 || code == 302) && (bodySnippet.contains("Zero Trust", ignoreCase = true) || bodySnippet.contains("cloudflareaccess")) -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "Cloudflare Zero Trust 拦截",
                        detailMessage = "检测到该域名启用了 Cloudflare Access 身份验证，手机端直接请求被拦截。",
                        tunnelHint = "【Zero Trust 排查建议】\n请在 Cloudflare One 控制台添加放行规则，或为 OpenCode 路由单独放行 API 路径/配置 Service Token 绕过浏览器登录认证。"
                    )
                )
            }
            isSakuraFrp && (code == 502 || bodySnippet.contains("隧道离线") || bodySnippet.contains("未备案")) -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "SakuraFrp 穿透异常 (HTTP $code)",
                        detailMessage = "穿透服务无法正常转发流量到本地服务器。",
                        tunnelHint = "【SakuraFrp 排查建议】\n1. 检查 SakuraFrp 客户端/节点是否在线，隧道是否开启；\n2. 确认隧道穿透的目标内网端口与 OpenCode 服务端口匹配；\n3. 若使用国内大陆节点的 HTTP 80/443 端口，请确认域名已完成 ICP 备案，否则会被拦截。"
                    )
                )
            }
            // 正常状态 (200..399 或 401/404 说明服务进程在正常监听)
            code in 200..399 || code == 401 || code == 404 || code == 405 -> {
                val tunnelType = when {
                    isCloudflare -> " (Cloudflare Tunnel 加速)"
                    isSakuraFrp -> " (SakuraFrp 穿透)"
                    else -> " (直连/公网反代)"
                }
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = true,
                        latencyMs = latency,
                        statusTitle = "网络连通性极佳$tunnelType",
                        detailMessage = "服务器端响应成功 (HTTP $code)，耗时 ${latency}ms。端口与网络路由通畅，可立即配对连线。",
                        tunnelHint = if (isCloudflare) "已检测到 Cloudflare 节点保护，WSS 长连接及流量加密均已就绪。" else null
                    )
                )
            }
            code == 502 -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "HTTP 502: Bad Gateway 网关错误",
                        detailMessage = "反向代理 (Nginx/Frp/Caddy) 已连通，但后端 OpenCode 服务未运行或端口拒绝连接。",
                        tunnelHint = "【排查建议】\n1. 请在 VPS 上执行 `ps aux | grep opencode` 确认服务存活；\n2. 检查反向代理配置中 proxy_pass 指向的端口是否正确。"
                    )
                )
            }
            else -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "服务器返回 HTTP $code",
                        detailMessage = "服务器已响应但状态异常: ${bodySnippet.take(120)}",
                        tunnelHint = "请检查服务器路由配置、URL 路径或访问鉴权 Token。"
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
                        statusTitle = "SSL/TLS 证书验证失败",
                        detailMessage = "握手异常: ${e.message ?: "证书链不受信或域名不匹配"}",
                        tunnelHint = "【SSL 证书排查建议】\n1. 若使用自签名证书，请在 Android 系统凭据中安装根证书，或改用 Let's Encrypt 免费受信任证书；\n2. 若配置了 Cloudflare SSL，请确认 SSL/TLS 模式设置为 Full (Strict) 或 Flexible。"
                    )
                )
            }
            is UnknownHostException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "域名解析失败 (DNS 无法解析)",
                        detailMessage = "找不到主机: ${e.message}",
                        tunnelHint = "【DNS 排查建议】\n1. 检查域名拼写是否有误；\n2. 确认域名 DNS 解析记录已生效；\n3. 切换手机当前网络 (Wi-Fi/移动蜂窝网络) 重新尝试。"
                    )
                )
            }
            is ConnectException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "连接被拒绝 (Connection Refused)",
                        detailMessage = "目标端口未开放监听，或本地服务未启动。",
                        tunnelHint = "【端口排查建议】\n1. 确认服务器端服务正在对应端口监听；\n2. 检查 VPS 云服务商控制台 (安全组/防火墙) 是否放行了该端口。"
                    )
                )
            }
            is SocketTimeoutException -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "连接超时 (Socket Timeout)",
                        detailMessage = "请求在 8 秒内未收到服务器响应。",
                        tunnelHint = "【超时排查建议】\n1. 检查目标 IP/端口是否被云厂商安全组屏蔽；\n2. 若使用了国内穿透 (SakuraFrp/frp)，确认穿透客户端与服务端之间网络正常。"
                    )
                )
            }
            else -> {
                postResult(
                    callback,
                    DiagnosticsResult(
                        isSuccess = false,
                        latencyMs = latency,
                        statusTitle = "网络探测异常",
                        detailMessage = "${e.javaClass.simpleName}: ${e.message ?: "网络链路异常"}",
                        tunnelHint = "请检查手机网络连接，并确认服务器公网地址可达。"
                    )
                )
            }
        }
    }

    private fun postResult(callback: (DiagnosticsResult) -> Unit, result: DiagnosticsResult) {
        mainHandler.post { callback(result) }
    }
}
