package com.opencode.android.data.local

/**
 * v3.2: 安全键值存储抽象。
 *
 * 旧实现：EncryptedSharedPreferences（androidx.security:security-crypto:1.1.0-alpha06，已被官方弃用，
 * 保留至少 1 个版本作迁移源与回退）。
 * 新实现：TinkAeadStore（com.google.crypto.tink:tink-android，官方推荐方向）。
 *
 * 抽象为纯 get/put/remove/allKeys，便于迁移逻辑做纯 JVM 单测。
 */
interface SecureKvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun allKeys(): Set<String>
}

/** 用普通 SharedPreferences 适配旧 EncryptedSharedPreferences（只读/写透传，不改语义） */
class LegacySecureStore(private val prefs: android.content.SharedPreferences) : SecureKvStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
    override fun allKeys(): Set<String> = prefs.all.keys
}
