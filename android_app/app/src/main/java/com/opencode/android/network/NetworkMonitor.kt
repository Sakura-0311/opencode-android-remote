package com.opencode.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * v2.3: 网络变化监听。v4.0 起常开（USE_NETWORK_MONITOR 回退开关已移除）。
 *
 * - 500ms 防抖：短暂抖动不触发重建。
 * - onLost：标记离线，调用方暂停重连计时器（不等 OkHttp ping 超时才发现）。
 * - onAvailable / 能力变化：调用方重建连接。
 */
class NetworkMonitor(context: Context) {

    interface Callback {
        fun onNetworkLost()
        fun onNetworkAvailable()
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var debounceRunnable: Runnable? = null
    private var lastNotified: Boolean? = null
    private var registered = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = postState(true)
        override fun onLost(network: Network) = postState(hasUsableNetwork())
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            postState(hasUsableNetwork())
        }
    }

    fun hasUsableNetwork(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun postState(up: Boolean) {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            debounceRunnable = null
            if (lastNotified != up) {
                lastNotified = up
                if (up) callback?.onNetworkAvailable() else callback?.onNetworkLost()
            }
        }
        debounceRunnable = r
        handler.postDelayed(r, 500L)
    }

    fun start(cb: Callback) {
        if (registered) return
        callback = cb
        lastNotified = hasUsableNetwork()
        cm.registerDefaultNetworkCallback(netCallback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
            // ignore
        }
        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = null
        registered = false
    }
}
