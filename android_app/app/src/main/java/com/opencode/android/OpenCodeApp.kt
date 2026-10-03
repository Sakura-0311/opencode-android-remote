package com.opencode.android

import android.app.Application
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.util.CrashReporting

/**
 * v1.6 P0 后台保活：
 * 网络连接（Relay WebSocket / Cloud SSE）由 Application 持有，
 * 与 Activity/ViewModel 生命周期解耦——Activity 被销毁不再误杀任务连接。
 * 前台服务负责保活进程，连接本身在应用进程内存活。
 */
class OpenCodeApp : Application() {

    val relayClient: RelayWebSocketClient by lazy { RelayWebSocketClient() }
    val cloudClient: CloudApiClient by lazy { CloudApiClient() }

    override fun onCreate() {
        super.onCreate()
        // 对外分发：ACRA 崩溃上报（用户手动开启后才初始化，默认关闭）
        CrashReporting.init(this)
        // v1.6 P0 断线恢复：尽早恢复持久化序号
        relayClient.setSeqPersistence(
            getSharedPreferences("relay_seq_store", MODE_PRIVATE)
        )
        cloudClient.setEventIdPersistence(
            getSharedPreferences("sse_event_store", MODE_PRIVATE)
        )
    }
}
