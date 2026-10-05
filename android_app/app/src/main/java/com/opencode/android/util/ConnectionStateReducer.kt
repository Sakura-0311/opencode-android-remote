package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.network.RelayConnectionState.*

/**
 * 阶段 1/P1: 连接状态纯映射（从 onConnectionStateChanged 抽出）。
 *
 * P0-4 状态机语义的唯一真相源：
 * - 哪些状态算"已连接"（顶部状态条/重连逻辑依赖）；
 * - 每种状态下 statusBanner 的动作。
 *
 * 文案由 ViewModel 经 getString 传入（红线），这里只做决策。
 */
object ConnectionStateReducer {

    /** 已连接判定：CONNECTED / AUTHENTICATING / AUTHENTICATED / DESKTOP_ONLINE */
    fun isConnected(state: RelayConnectionState): Boolean = when (state) {
        CONNECTED, AUTHENTICATING, AUTHENTICATED, DESKTOP_ONLINE -> true
        else -> false
    }

    /** statusBanner 动作 */
    enum class BannerAction {
        SHOW_CONNECTING,      // vm_016
        SHOW_CONNECTED,       // vm_023
        SHOW_AUTHENTICATING,  // vm_025
        KEEP,                 // 保持当前（重连倒计时等）
        CLEAR                 // 置空
    }

    fun bannerAction(state: RelayConnectionState): BannerAction = when (state) {
        CONNECTING -> BannerAction.SHOW_CONNECTING
        CONNECTED -> BannerAction.SHOW_CONNECTED
        AUTHENTICATING -> BannerAction.SHOW_AUTHENTICATING
        RECONNECTING -> BannerAction.KEEP
        AUTH_FAILED -> BannerAction.CLEAR
        DISCONNECTED -> BannerAction.CLEAR
        else -> BannerAction.KEEP // AUTHENTICATED / DESKTOP_ONLINE 保持
    }

    /** 三段文案由调用方按 BannerAction 取 getString(R.string.vm_0xx) */
    data class ConnectionBanners(
        val connecting: String,
        val connected: String,
        val authenticating: String
    )

    /** onConnectionStateChanged 的纯状态部分 */
    fun applyConnectionState(
        state: OpenCodeUiState,
        connState: RelayConnectionState,
        banners: ConnectionBanners
    ): OpenCodeUiState {
        val banner = when (bannerAction(connState)) {
            BannerAction.SHOW_CONNECTING -> banners.connecting
            BannerAction.SHOW_CONNECTED -> banners.connected
            BannerAction.SHOW_AUTHENTICATING -> banners.authenticating
            BannerAction.KEEP -> state.statusBanner
            BannerAction.CLEAR -> null
        }
        return state.copy(
            relayConnectionState = connState,
            isRelayConnected = isConnected(connState),
            isReconnecting = connState == RECONNECTING,
            statusBanner = banner
        )
    }
}
