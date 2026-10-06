package com.opencode.android.network

import kotlin.math.min

/**
 * v2.3: 统一指数退避（Relay WS 与 Cloud SSE 共用）。
 *
 * 纯 Kotlin、无 Android 依赖，可单测。
 * 不可重试错误（鉴权失败 4401 / 被封禁 4429 / 用户主动断开）由调用方分类后直接停止，
 * 不要喂给退避器。
 *
 * v5.0.3 (B-1)：加最大重试次数。耗尽时 [nextDelayMs] 返回 null，
 * 调用方转「停止重连」状态（Relay 转 DISCONNECTED 并等用户手动重连）。
 * 默认 [MAX_RETRIES_UNLIMITED]：Cloud SSE 的停止条件是「持续离线 30 分钟」，
 * 不受次数约束，保持原行为。
 */
class Backoff(
    private val baseMs: Long = 3000L,
    private val factor: Double = 1.5,
    private val maxMs: Long = 60000L,
    private val jitterLow: Double = 0.85,
    private val jitterHigh: Double = 1.15,
    private val maxRetries: Int = MAX_RETRIES_UNLIMITED,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(jitterLow, jitterHigh) }
) {
    private var currentMs: Long = baseMs

    /** v5.0.3: 已消耗的重试次数（reset 后归零） */
    var retryCount: Int = 0
        private set

    /** 是否已用尽重试次数 */
    fun isExhausted(): Boolean = retryCount >= maxRetries

    /**
     * 返回本次应等待的毫秒数（含抖动）并推进到下一档；
     * 已用尽重试次数时返回 null（调用方必须停止重连）。
     */
    fun nextDelayMs(): Long? {
        if (isExhausted()) return null
        retryCount++
        val delay = (min(currentMs.toDouble(), maxMs.toDouble()) * jitter()).toLong()
        currentMs = min(maxMs, (currentMs * factor).toLong())
        return delay.coerceAtLeast(0L)
    }

    /**
     * 连接**确实建立**（v5.0.3：必须是 auth_ok 之后，而不是 WS 握手成功）后调用：
     * 回到初始档位并清零计数。
     */
    fun reset() {
        currentMs = baseMs
        retryCount = 0
    }

    companion object {
        /** 不限重试次数（Cloud SSE 用：它的停止条件是持续离线时长，不是次数） */
        const val MAX_RETRIES_UNLIMITED = Int.MAX_VALUE

        /** 不可重试的关闭码：鉴权失败 / IP 被封禁 */
        fun isNonRetryableCloseCode(code: Int): Boolean = code == 4401 || code == 4429

        /** 不可重试的错误文本（auth_error 等） */
        fun isNonRetryableMessage(msg: String): Boolean {
            val m = msg.lowercase()
            return m.contains("auth_error") || m.contains("4401") || m.contains("4429")
        }
    }
}
