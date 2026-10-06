package com.opencode.android.data.local

/**
 * 安全键值存储抽象。
 *
 * 实现只有一个：TinkAeadStore（com.google.crypto.tink:tink-android，主密钥由
 * Android Keystore 保护）。
 *
 * v5.0.2: 删除旧的 EncryptedSharedPreferences 适配实现（LegacySecureStore）与
 * androidx.security:security-crypto 依赖——项目尚无线上用户，没有需要迁移或
 * 回退的存量数据。抽象保留 get/put/remove/allKeys 是为了便于纯 JVM 单测。
 */
interface SecureKvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun allKeys(): Set<String>
}
