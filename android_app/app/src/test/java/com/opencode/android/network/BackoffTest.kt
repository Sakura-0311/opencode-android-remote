package com.opencode.android.network

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.3: Backoff 退避器单测（characterization：锁定 v2.2 的退避参数行为）。
 * v5.0.3 (B-1)：补最大重试次数与「握手成功不算 reset」的语义。
 */
class BackoffTest {

    @Test
    fun `relay params match v2_2 behavior`() {
        // v2.2: 3s 起、×1.5、封顶 60s、±15% 抖动
        val b = Backoff(baseMs = 3000L, factor = 1.5, maxMs = 60000L,
            jitterLow = 0.85, jitterHigh = 1.15, jitter = { 1.0 })
        assertEquals(3000L, b.nextDelayMs()!!)
        assertEquals(4500L, b.nextDelayMs()!!)
        assertEquals(6750L, b.nextDelayMs()!!)
    }

    @Test
    fun `delay never exceeds max`() {
        val b = Backoff(baseMs = 3000L, factor = 1.5, maxMs = 60000L, jitter = { 1.15 })
        repeat(20) {
            assertTrue(b.nextDelayMs()!! <= (60000L * 1.15).toLong() + 1)
        }
    }

    @Test
    fun `reset returns to base`() {
        val b = Backoff(jitter = { 1.0 })
        b.nextDelayMs()
        b.nextDelayMs()
        b.reset()
        assertEquals(3000L, b.nextDelayMs()!!)
    }

    @Test
    fun `sse params grow 1s 2s 4s capped 30s`() {
        val b = Backoff(baseMs = 1000L, factor = 2.0, maxMs = 30000L, jitter = { 1.0 })
        assertEquals(1000L, b.nextDelayMs()!!)
        assertEquals(2000L, b.nextDelayMs()!!)
        assertEquals(4000L, b.nextDelayMs()!!)
        assertEquals(8000L, b.nextDelayMs()!!)
        assertEquals(16000L, b.nextDelayMs()!!)
        assertEquals(30000L, b.nextDelayMs()!!)
        assertEquals(30000L, b.nextDelayMs()!!)
    }

    @Test
    fun `non retryable close codes`() {
        assertTrue(Backoff.isNonRetryableCloseCode(4401))
        assertTrue(Backoff.isNonRetryableCloseCode(4429))
        assertFalse(Backoff.isNonRetryableCloseCode(1000))
        assertFalse(Backoff.isNonRetryableCloseCode(1006))
    }

    @Test
    fun `non retryable messages`() {
        assertTrue(Backoff.isNonRetryableMessage("auth_error: Invalid secret"))
        assertTrue(Backoff.isNonRetryableMessage("closed 4401"))
        assertFalse(Backoff.isNonRetryableMessage("connection reset"))
    }

    // ---- v5.0.3 (B-1)：最大重试次数 ----

    @Test
    fun `default is unlimited`() {
        val b = Backoff(jitter = { 1.0 })
        repeat(50) { assertNotNull("默认不限次数", b.nextDelayMs()) }
        assertFalse(b.isExhausted())
    }

    @Test
    fun `exhausted returns null and stops`() {
        val b = Backoff(maxRetries = 3, jitter = { 1.0 })
        assertEquals(3000L, b.nextDelayMs()!!)
        assertEquals(4500L, b.nextDelayMs()!!)
        assertEquals(6750L, b.nextDelayMs()!!)
        assertTrue(b.isExhausted())
        assertNull("耗尽后必须返回 null，调用方据此停止重连", b.nextDelayMs())
        assertEquals(3, b.retryCount)
    }

    @Test
    fun `reset clears retry count`() {
        val b = Backoff(maxRetries = 2, jitter = { 1.0 })
        b.nextDelayMs()
        b.nextDelayMs()
        assertTrue(b.isExhausted())
        b.reset()
        assertFalse(b.isExhausted())
        assertEquals(0, b.retryCount)
        assertEquals(3000L, b.nextDelayMs()!!)
    }

    /**
     * B-1 回归：握手成功但鉴权前被断开时，退避必须递增。
     * 若在 WS 握手成功时就 reset()，每次断开都会从 3 秒重新开始，
     * 等价于固定 3 秒一次无限重连——这正是修复前的行为。
     */
    @Test
    fun `repeated handshake-then-drop escalates instead of restarting at base`() {
        val b = Backoff(maxRetries = 10, jitter = { 1.0 })
        val first = b.nextDelayMs()!!
        // 服务端接受连接后立刻关闭：不 reset，模拟连续失败
        val second = b.nextDelayMs()!!
        val third = b.nextDelayMs()!!
        assertEquals(3000L, first)
        assertTrue("第二次必须大于第一次（未回落到 base）", second > first)
        assertTrue("第三次必须大于第二次", third > second)
    }
}