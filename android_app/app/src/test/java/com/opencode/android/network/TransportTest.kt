package com.opencode.android.network

import com.opencode.android.data.model.AppMode
import org.junit.Assert.*
import org.junit.Test

/**
 * v3.0: Transport 切换单测（纯逻辑，不依赖 Android 框架）。
 */
class TransportTest {

    /** 最小 fake Transport，只验证 Factory 的选择逻辑 */
    private class FakeTransport(override val name: String) : Transport {
        var connectCalls = 0
        override fun connect(params: TransportParams, listener: TransportListener) { connectCalls++ }
        override fun send(message: TransportMessage): Boolean = true
        override fun close() {}
        override fun isConnected(): Boolean = true
    }

    @Test
    fun `DESKTOP_RELAY selects relay transport`() {
        val relay = FakeTransport("relay")
        val cloud = FakeTransport("cloud")
        val t = TransportFactory.select(AppMode.DESKTOP_RELAY, relay, cloud)
        assertSame(relay, t)
        assertEquals("relay", t.name)
    }

    @Test
    fun `CLOUD_DIRECT selects cloud transport`() {
        val relay = FakeTransport("relay")
        val cloud = FakeTransport("cloud")
        val t = TransportFactory.select(AppMode.CLOUD_HOSTED, relay, cloud)
        assertSame(cloud, t)
        assertEquals("cloud", t.name)
    }

    @Test
    fun `connect delegates to selected transport`() {
        val relay = FakeTransport("relay")
        val cloud = FakeTransport("cloud")
        val listener = object : TransportListener {
            override fun onTransportStateChanged(connected: Boolean, detail: String) {}
            override fun onTransportError(code: String, message: String) {}
        }
        val params = TransportParams(relayUrl = "ws://x", accountId = "a", secret = "s")
        TransportFactory.select(AppMode.DESKTOP_RELAY, relay, cloud).connect(params, listener)
        assertEquals(1, relay.connectCalls)
        assertEquals(0, cloud.connectCalls)
    }

    @Test
    fun `protocol constants are v4`() {
        assertEquals(4, RelayWebSocketClient.PROTOCOL_VERSION)
        assertTrue(RelayWebSocketClient.CLIENT_CAPABILITIES.contains("hello"))
        assertTrue(RelayWebSocketClient.CLIENT_CAPABILITIES.contains("write_idempotency"))
    }
}
