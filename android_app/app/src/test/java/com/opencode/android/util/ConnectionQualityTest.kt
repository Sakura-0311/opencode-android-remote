package com.opencode.android.util

import com.opencode.android.data.model.ConnectionQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.1 (优化方案 §3.2): 连接质量分级单测（纯 JVM）。
 * 覆盖 ConnectionQuality.fromLatency 的阈值边界：
 * - null -> UNKNOWN
 * - < 150ms -> GOOD
 * - 150~499ms -> FAIR
 * - >= 500ms -> POOR
 */
class ConnectionQualityTest {

    @Test
    fun `null latency is UNKNOWN`() {
        assertEquals(ConnectionQuality.UNKNOWN, ConnectionQuality.fromLatency(null))
    }

    @Test
    fun `below 150ms is GOOD`() {
        assertEquals(ConnectionQuality.GOOD, ConnectionQuality.fromLatency(0L))
        assertEquals(ConnectionQuality.GOOD, ConnectionQuality.fromLatency(149L))
    }

    @Test
    fun `150 to 499ms is FAIR`() {
        assertEquals(ConnectionQuality.FAIR, ConnectionQuality.fromLatency(150L))
        assertEquals(ConnectionQuality.FAIR, ConnectionQuality.fromLatency(499L))
    }

    @Test
    fun `500ms and above is POOR`() {
        assertEquals(ConnectionQuality.POOR, ConnectionQuality.fromLatency(500L))
        assertEquals(ConnectionQuality.POOR, ConnectionQuality.fromLatency(5000L))
    }
}
