package com.opencode.android.util

/**
 * v3.1: 多 desktop 定向路由的纯决策逻辑（无 Android 依赖，可 JVM 单测）。
 */
object DesktopRoutingPolicy {

    /** v3 能力名（relay server.py SERVER_CAPABILITIES） */
    const val CAPABILITY = "desktop_routing"

    /**
     * 是否启用定向路由：开关开 && 服务端支持 && 已选目标。
     * 任一不满足则退回 v3.0 的主 desktop 路由。
     */
    fun shouldRouteToTarget(
        flagEnabled: Boolean,
        serverSupports: Boolean,
        targetDeviceId: String
    ): Boolean = flagEnabled && serverSupports && targetDeviceId.isNotEmpty()

    /** 算出真正要传给 sendPrompt 的 target；不满足条件时返回 null（走主） */
    fun resolveTarget(
        flagEnabled: Boolean,
        serverSupports: Boolean,
        targetDeviceId: String
    ): String? =
        if (shouldRouteToTarget(flagEnabled, serverSupports, targetDeviceId)) targetDeviceId else null

    /**
     * 目标电脑离线时的针对性提示文案。必须包含目标标识（名称或 device_id），
     * 区别于笼统的「电脑端未连接」。
     */
    fun offlineHint(targetName: String, targetDeviceId: String, serverMessage: String): String {
        val label = targetName.ifBlank { targetDeviceId.take(8).ifBlank { "未知设备" } }
        val base = serverMessage.ifBlank { "目标电脑当前不在线，无法执行指令。" }
        return "目标电脑「$label」($targetDeviceId)：$base"
    }

    /** device_id 缩写（前 8 位），用于列表展示 */
    fun shortId(deviceId: String): String = deviceId.take(8)
}
