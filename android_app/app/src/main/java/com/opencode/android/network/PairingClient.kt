package com.opencode.android.network

import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * v1.6 P0 一键扫码配对：一次性配对认领客户端
 *
 * 流程：手机扫码 → 显示确认 → 发送 pair_claim（一次性短期 token）
 *      → Relay 验证 → 返回设备专用密钥 → 手机保存到 Keystore
 * 二维码中不包含长期 Secret，token 一次性、120 秒过期。
 */
data class PairClaimResult(
    val success: Boolean,
    val deviceSecret: String = "",
    val accountId: String = "",
    val desktopName: String = "",
    val error: String = ""
)

object PairingClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun claimPairing(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }
    ): PairClaimResult = suspendCancellableCoroutine { cont ->
        val wsEndpoint = if (relayUrl.trim().removeSuffix("/").endsWith("/mobile")) {
            relayUrl.trim().removeSuffix("/")
        } else {
            "${relayUrl.trim().removeSuffix("/")}/ws/$accountId/mobile"
        }
        val request = Request.Builder().url(wsEndpoint).build()
        var ws: WebSocket? = null

        fun finish(r: PairClaimResult) {
            if (cont.isActive) {
                try { ws?.close(1000, "Pairing done") } catch (_: Exception) {}
                cont.resume(r)
            }
        }

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val claim = JSONObject().apply {
                    put("type", "pair_claim")
                    put("account_id", accountId)
                    put("pairing_token", pairingToken)
                    put("device_name", deviceName.take(64))
                }
                webSocket.send(claim.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "pair_success" -> finish(PairClaimResult(
                            success = true,
                            deviceSecret = json.optString("device_secret"),
                            accountId = json.optString("account_id", accountId),
                            desktopName = json.optString("desktop_name", "Desktop")
                        ))
                        "pair_error" -> finish(PairClaimResult(
                            success = false,
                            error = json.optString("message", "配对失败")
                        ))
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(PairClaimResult(
                    success = false,
                    error = t.localizedMessage ?: "连接失败"
                ))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) {
                    finish(PairClaimResult(success = false, error = "连接已关闭"))
                }
            }
        })
        cont.invokeOnCancellation { try { ws?.cancel() } catch (_: Exception) {} }
    }
}
