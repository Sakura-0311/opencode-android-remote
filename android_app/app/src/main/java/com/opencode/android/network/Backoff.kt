package com.opencode.android.network

import kotlin.math.min

/**
 * v2.3: 统一指数退避（Relay WS 与 Cloud SSE 共用）。
 *
 * 纯 Kotlin、无 Android 依赖，可单测。
 * 不可重试错误（鉴权失败 4401 / 被封禁 4429 / 用户主动断开）由调用方分类后直接停止，
 * 不要喂给退避器。
 */
class Backoff(
    private val baseMs: Long = 3000L,
    private val factor: Double = 1.5,
    private val maxMs: Long = 60000L,
    private val jitterLow: Double = 0.85,
    private val jitterHigh: Double = 1.15,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(jitterLow, jitterHigh) }
) {
    private var currentMs: Long = baseMs

    /** 返回本次应等待的毫秒数（含抖动），并推进到下一档 */
    fun nextDelayMs(): Long {
        val delay = (min(currentMs.toDouble(), maxMs.toDouble()) * jitter()).toLong()
        currentMs = min(maxMs, (currentMs * factor).toLong())
        return delay.coerceAtLeast(0L)
    }

    /** 连接成功后调用，回到初始档位 */
    fun reset() {
        currentMs = baseMs
    }

    companion object {
        /** 不可重试的关闭码：鉴权失败 / IP 被封禁 */
        fun isNonRetryableCloseCode(code: Int): Boolean = code == 4401 || code == 4429

        /** 不可重试的错误文本（auth_error 等） */
        fun isNonRetryableMessage(msg: String): Boolean {
            val m = msg.lowercase()
            return m.contains("auth_error") || m.contains("4401") || m.contains("4429")
        }
    }
}
