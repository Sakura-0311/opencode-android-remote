package com.opencode.android.network

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.3: Backoff 退避器单测（characterization：锁定 v2.2 的退避参数行为）。
 */
class BackoffTest {

    @Test
    fun `relay params match v2_2 behavior`() {
        // v2.2: 3s 起、×1.5、封顶 60s、±15% 抖动
        val b = Backoff(baseMs = 3000L, factor = 1.5, maxMs = 60000L,
            jitterLow = 0.85, jitterHigh = 1.15, jitter = { 1.0 })
        assertEquals(3000L, b.nextDelayMs())
        assertEquals(4500L, b.nextDelayMs())
        assertEquals(6750L, b.nextDelayMs())
    }

    @Test
    fun `delay never exceeds max`() {
        val b = Backoff(baseMs = 3000L, factor = 1.5, maxMs = 60000L, jitter = { 1.15 })
        repeat(20) {
            assertTrue(b.nextDelayMs() <= (60000L * 1.15).toLong() + 1)
        }
    }

    @Test
    fun `reset returns to base`() {
        val b = Backoff(jitter = { 1.0 })
        b.nextDelayMs()
        b.nextDelayMs()
        b.reset()
        assertEquals(3000L, b.nextDelayMs())
    }

    @Test
    fun `sse params grow 1s 2s 4s capped 30s`() {
        val b = Backoff(baseMs = 1000L, factor = 2.0, maxMs = 30000L, jitter = { 1.0 })
        assertEquals(1000L, b.nextDelayMs())
        assertEquals(2000L, b.nextDelayMs())
        assertEquals(4000L, b.nextDelayMs())
        assertEquals(8000L, b.nextDelayMs())
        assertEquals(16000L, b.nextDelayMs())
        assertEquals(30000L, b.nextDelayMs())
        assertEquals(30000L, b.nextDelayMs())
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
}
