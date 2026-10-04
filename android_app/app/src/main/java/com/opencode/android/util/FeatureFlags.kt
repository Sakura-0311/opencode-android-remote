package com.opencode.android.util

/**
 * v4.0: 功能开关。USE_NETWORK_MONITOR（v2.3 引入）已长期稳定，本版移除回退路径，
 * NetworkMonitor 常开。ENABLE_DESKTOP_ROUTING 仍为新功能 opt-in 开关。
 */
object FeatureFlags {
    /** v3.1: 多 desktop 定向路由。false 时保持 v3.0 的主 desktop 路由行为 */
    const val ENABLE_DESKTOP_ROUTING = false
}
