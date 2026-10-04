package com.opencode.android.data.local

import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead

/**
 * v3.2: Tink AEAD 加密存储。
 *
 * 值以 AES256-GCM 加密后 Base64 存入普通 SharedPreferences；
 * associatedData 取 key 名，防止键值被调包复制。
 * Aead 由调用方注入（生产走 TinkKeyManager 的 Android Keystore 主密钥；单测可注入假实现）。
 * Base64 编解码同样可注入：生产用 android.util.Base64（minSdk 24 无 java.util.Base64），
 * 单测注入 java.util.Base64（JVM 的 android stub 会抛异常）。
 */
class TinkAeadStore(
    private val aead: Aead,
    private val backing: SharedPreferences,
    private val keyPrefix: String = "tinkv1_",
    private val b64: B64Codec = AndroidB64
) : SecureKvStore {

    interface B64Codec {
        fun encode(bytes: ByteArray): String
        fun decode(s: String): ByteArray
    }

    object AndroidB64 : B64Codec {
        override fun encode(bytes: ByteArray): String =
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        override fun decode(s: String): ByteArray =
            android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    }

    private fun storageKey(key: String) = keyPrefix + key

    override fun get(key: String): String? {
        val b64str = backing.getString(storageKey(key), null) ?: return null
        return try {
            val cipher = b64.decode(b64str)
            String(aead.decrypt(cipher, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("TinkAeadStore", "解密失败 key=$key", e)
            null
        }
    }

    override fun put(key: String, value: String) {
        val cipher = aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        backing.edit().putString(storageKey(key), b64.encode(cipher)).apply()
    }

    override fun remove(key: String) {
        backing.edit().remove(storageKey(key)).apply()
    }

    override fun allKeys(): Set<String> {
        return backing.all.keys
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix(keyPrefix) }
            .toSet()
    }
}
