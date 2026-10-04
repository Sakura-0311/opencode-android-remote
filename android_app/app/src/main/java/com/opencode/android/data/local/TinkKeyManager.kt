package com.opencode.android.data.local

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * v3.2: Tink 密钥管理。
 *
 * AEAD 主密钥由 Android Keystore 保护（alias=tink_master_key），
 * keyset 存私有的 SharedPreferences 文件。任一步失败抛异常，由调用方回退旧实现。
 */
object TinkKeyManager {
    const val TINK_VERSION = "1.23.0"
    private const val KEYSET_PREF_FILE = "opencode_tink_keyset"
    private const val KEYSET_PREF_NAME = "tink_keyset_handle"
    private const val MASTER_KEY_URI = "android-keystore://tink_master_key"

    @Volatile
    private var cached: Aead? = null

    @Synchronized
    @Throws(Exception::class)
    fun getOrCreateAead(context: Context): Aead {
        cached?.let { return it }
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_PREF_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        val aead = handle.getPrimitive(Aead::class.java)
        // 自检：一次加解密 round-trip，失败直接抛给调用方回退
        val probe = aead.encrypt("tink-probe".toByteArray(), "probe".toByteArray())
        val plain = aead.decrypt(probe, "probe".toByteArray())
        check(String(plain) == "tink-probe") { "Tink AEAD 自检失败" }
        cached = aead
        return aead
    }
}
