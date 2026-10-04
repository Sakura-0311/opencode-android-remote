package com.opencode.android.data.local

/**
 * v3.1: 目标电脑选择与「会话—电脑」绑定的持久化。
 * 目标选择按 profile 隔离（key: target_desktop_<profileId>）；会话绑定按 sessionId 存。
 * 存储后端可注入（Kv），纯逻辑可 JVM 单测；生产环境由 PreferencesManager 传入
 * SharedPreferences 适配实现。
 */
class TargetDesktopStore(private val kv: Kv) {

    interface Kv {
        fun getString(key: String, def: String): String
        fun putString(key: String, value: String)
    }

    /** 保存目标电脑；空字符串表示未选择（走主 desktop） */
    fun saveTargetDesktopId(profileId: String, deviceId: String) {
        kv.putString(KEY_TARGET_DESKTOP + profileId, deviceId)
    }

    fun getTargetDesktopId(profileId: String): String =
        kv.getString(KEY_TARGET_DESKTOP + profileId, "")

    /** 会话与电脑绑定（切换目标时记录） */
    fun saveSessionBinding(sessionId: String, deviceId: String) {
        kv.putString(KEY_SESSION_BINDING + sessionId, deviceId)
    }

    fun getSessionBinding(sessionId: String): String =
        kv.getString(KEY_SESSION_BINDING + sessionId, "")

    companion object {
        private const val KEY_TARGET_DESKTOP = "target_desktop_"
        private const val KEY_SESSION_BINDING = "session_desktop_binding_"
    }
}
