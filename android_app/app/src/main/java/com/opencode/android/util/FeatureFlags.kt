package com.opencode.android.util

/**
 * v2.3: 功能开关。重构保留旧路径一个版本，便于回滚。
 */
object FeatureFlags {
    /** 网络监听（NetworkMonitor）：false 时回到 v2.2 行为（无网络回调） */
    const val USE_NETWORK_MONITOR = true
}
