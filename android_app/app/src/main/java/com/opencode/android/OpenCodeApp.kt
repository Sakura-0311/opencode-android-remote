package com.opencode.android

import android.app.Application
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.NetworkMonitor
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.util.AppLog
import com.opencode.android.util.CrashReporting
import com.opencode.android.util.FeatureFlags
import java.io.File

/**
 * v1.6 P0 后台保活：
 * 网络连接（Relay WebSocket / Cloud SSE）由 Application 持有，
 * 与 Activity/ViewModel 生命周期解耦——Activity 被销毁不再误杀任务连接。
 * 前台服务负责保活进程，连接本身在应用进程内存活。
 */
class OpenCodeApp : Application() {

    val relayClient: RelayWebSocketClient by lazy { RelayWebSocketClient(this) }
    val cloudClient: CloudApiClient by lazy { CloudApiClient(this) }

    // v2.3: 网络变化监听（500ms 防抖），驱动 relay 重连
    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        // v2.3: 环形文件日志（2×1MB，脱敏）
        AppLog.init(File(cacheDir, "applog"))
        // 对外分发：ACRA 崩溃上报（用户手动开启后才初始化，默认关闭）
        CrashReporting.init(this)
        // v1.6 P0 断线恢复：尽早恢复持久化序号
        relayClient.setSeqPersistence(
            getSharedPreferences("relay_seq_store", MODE_PRIVATE)
        )
        // v4.1: E2EE 管理器注入（FeatureFlags.ENABLE_E2EE 门控，默认关闭）
        relayClient.setE2eeManager(
            com.opencode.android.security.E2eeManager(
                com.opencode.android.data.local.PreferencesManager.getInstance(this)
            )
        )
        cloudClient.setEventIdPersistence(
            getSharedPreferences("sse_event_store", MODE_PRIVATE)
        )
        // v4.0: 网络回调常开——断网暂停重连，恢复时重建连接（不等 ping 超时）
        // （v2.3 引入的 USE_NETWORK_MONITOR 回退开关已移除）
        networkMonitor = NetworkMonitor(this).also { monitor ->
            monitor.start(object : NetworkMonitor.Callback {
                override fun onNetworkLost() = relayClient.onNetworkLost()
                override fun onNetworkAvailable() = relayClient.onNetworkAvailable()
            })
        }
        AppLog.i("App", "NetworkMonitor started")
    }
}
