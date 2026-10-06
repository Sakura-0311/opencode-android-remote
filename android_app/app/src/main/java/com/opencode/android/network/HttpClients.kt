package com.opencode.android.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * v5.0.3 (P3): 全进程共用一个 OkHttpClient。
 *
 * 此前 Relay 与 Cloud 各建一个，连接池与线程池各一套——同一进程内对同一批
 * 域名建立两套连接。子 client 用 [OkHttpClient.newBuilder] 派生：它共享
 * 连接池、Dispatcher 与线程池，只覆盖各自的超时/心跳设置。
 */
object HttpClients {

    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}