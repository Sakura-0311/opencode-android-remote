package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.util.ConnectionStateReducer.BannerAction
import com.opencode.android.util.ConnectionStateReducer.ConnectionBanners
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P1: ConnectionStateReducer 单测（先补测试再拆）。
 * P0-4 状态机语义：8 种状态全覆盖。
 */
class ConnectionStateReducerTest {

    private val base = OpenCodeUiState()
    private val banners = ConnectionBanners(
        connecting = "B_CONNECTING",
        connected = "B_CONNECTED",
        authenticating = "B_AUTH"
    )

    // ---- isConnected ----

    @Test fun isConnected_trueStates() {
        for (s in listOf(
            RelayConnectionState.CONNECTED,
            RelayConnectionState.AUTHENTICATING,
            RelayConnectionState.AUTHENTICATED,
            RelayConnectionState.DESKTOP_ONLINE
        )) assertTrue("expected connected: $s", ConnectionStateReducer.isConnected(s))
    }

    @Test fun isConnected_falseStates() {
        for (s in listOf(
            RelayConnectionState.DISCONNECTED,
            RelayConnectionState.CONNECTING,
            RelayConnectionState.RECONNECTING,
            RelayConnectionState.AUTH_FAILED
        )) assertFalse("expected not connected: $s", ConnectionStateReducer.isConnected(s))
    }

    // ---- bannerAction: 8 种状态全覆盖 ----

    @Test fun bannerAction_mapping() {
        assertEquals(BannerAction.SHOW_CONNECTING, ConnectionStateReducer.bannerAction(RelayConnectionState.CONNECTING))
        assertEquals(BannerAction.SHOW_CONNECTED, ConnectionStateReducer.bannerAction(RelayConnectionState.CONNECTED))
        assertEquals(BannerAction.SHOW_AUTHENTICATING, ConnectionStateReducer.bannerAction(RelayConnectionState.AUTHENTICATING))
        assertEquals(BannerAction.KEEP, ConnectionStateReducer.bannerAction(RelayConnectionState.RECONNECTING))
        assertEquals(BannerAction.CLEAR, ConnectionStateReducer.bannerAction(RelayConnectionState.AUTH_FAILED))
        assertEquals(BannerAction.CLEAR, ConnectionStateReducer.bannerAction(RelayConnectionState.DISCONNECTED))
        // AUTHENTICATED / DESKTOP_ONLINE 保持当前 banner
        assertEquals(BannerAction.KEEP, ConnectionStateReducer.bannerAction(RelayConnectionState.AUTHENTICATED))
        assertEquals(BannerAction.KEEP, ConnectionStateReducer.bannerAction(RelayConnectionState.DESKTOP_ONLINE))
    }

    // ---- applyConnectionState ----

    @Test fun apply_authenticatedKeepsBanner() {
        val state = base.copy(statusBanner = "倒计时 5s")
        val out = ConnectionStateReducer.applyConnectionState(state, RelayConnectionState.AUTHENTICATED, banners)
        assertEquals(RelayConnectionState.AUTHENTICATED, out.relayConnectionState)
        assertTrue(out.isRelayConnected)
        assertFalse(out.isReconnecting)
        assertEquals("倒计时 5s", out.statusBanner) // KEEP 语义：重连倒计时文案不被冲掉
    }

    @Test fun apply_connectingShowsBanner() {
        val out = ConnectionStateReducer.applyConnectionState(base, RelayConnectionState.CONNECTING, banners)
        assertEquals("B_CONNECTING", out.statusBanner)
        assertFalse(out.isRelayConnected)
        assertFalse(out.isReconnecting)
    }

    @Test fun apply_reconnectingSetsFlagKeepsBanner() {
        val state = base.copy(statusBanner = "倒计时 3s")
        val out = ConnectionStateReducer.applyConnectionState(state, RelayConnectionState.RECONNECTING, banners)
        assertTrue(out.isReconnecting)
        assertFalse(out.isRelayConnected)
        assertEquals("倒计时 3s", out.statusBanner)
    }

    @Test fun apply_authFailedClearsBanner() {
        val state = base.copy(statusBanner = "x")
        val out = ConnectionStateReducer.applyConnectionState(state, RelayConnectionState.AUTH_FAILED, banners)
        assertNull(out.statusBanner)
        assertFalse(out.isRelayConnected)
        assertFalse(out.isReconnecting)
        assertEquals("x", state.statusBanner) // 不变性
    }

    @Test fun apply_disconnectedClearsBanner() {
        val state = base.copy(statusBanner = "x", isRelayConnected = true)
        val out = ConnectionStateReducer.applyConnectionState(state, RelayConnectionState.DISCONNECTED, banners)
        assertNull(out.statusBanner)
        assertFalse(out.isRelayConnected)
    }
}
