package com.opencode.android.network

import com.google.crypto.tink.Aead
import com.opencode.android.data.local.SecureKvStore
import com.opencode.android.data.local.SecureMigration
import com.opencode.android.data.local.TinkAeadStore
import org.junit.Assert.*
import org.junit.Test

/**
 * v3.2: 安全存储迁移单测（纯 JVM）。
 * TinkAeadStore 注入假 Aead；迁移逻辑不依赖 Android Keystore。
 */
class SecureMigrationTest {

    /** 内存 KV，供迁移逻辑测试 */
    private class MemStore : SecureKvStore {
        val map = mutableMapOf<String, String>()
        var failOnPut: String? = null
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            if (key == failOnPut) throw RuntimeException("模拟写入失败")
            map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun allKeys() = map.keys.toSet()
    }

    /** 假 Aead：异或混淆（仅测试序列化路径，不做真实加密） */
    private class FakeAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            val ad = associatedData.fold(0) { a, b -> a + b }
            return plaintext.map { (it + ad).toByte() }.toByteArray()
        }
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val ad = associatedData.fold(0) { a, b -> a + b }
            return ciphertext.map { (it - ad).toByte() }.toByteArray()
        }
    }

    /** JVM 用的 Base64（android.util.Base64 在单测 classpath 上是 stub） */
    private object JvmB64 : com.opencode.android.data.local.TinkAeadStore.B64Codec {
        override fun encode(bytes: ByteArray) =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
        override fun decode(s: String) = java.util.Base64.getDecoder().decode(s)
    }

    private class MemPrefs : android.content.SharedPreferences {
        val map = mutableMapOf<String, String>()
        override fun getAll() = map.toMap()
        override fun getString(k: String?, d: String?) = if (k == null) d else map[k] ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = d
        override fun getLong(k: String?, d: Long) = d
        override fun getFloat(k: String?, d: Float) = d
        override fun getBoolean(k: String?, d: Boolean) = d
        override fun contains(k: String?) = k != null && map.containsKey(k)
        override fun edit() = object : android.content.SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { if (k != null) { if (v == null) map.remove(k) else map[k] = v } }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = this
            override fun putInt(k: String?, v: Int) = this
            override fun putLong(k: String?, v: Long) = this
            override fun putFloat(k: String?, v: Float) = this
            override fun putBoolean(k: String?, v: Boolean) = this
            override fun remove(k: String?) = apply { map.remove(k) }
            override fun clear() = apply { map.clear() }
            override fun commit() = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun `migrate copies all keys and verifies`() {
        val old = MemStore().apply {
            map["secret"] = "s3cr3t"
            map["account_id"] = "acc-1"
            map["cloud_api_key_profile_x"] = "key-xyz"
        }
        val new = MemStore()
        val r = SecureMigration.migrate(old, new)
        assertTrue(r is SecureMigration.Result.Success)
        assertEquals(setOf("secret", "account_id", "cloud_api_key_profile_x"),
            (r as SecureMigration.Result.Success).migratedKeys.toSet())
        assertEquals("s3cr3t", new.get("secret"))
        // 旧数据保留（回退用）
        assertEquals("s3cr3t", old.get("secret"))
    }

    @Test
    fun `migrate is idempotent`() {
        val old = MemStore().apply { map["a"] = "1"; map["b"] = "2" }
        val new = MemStore()
        val r1 = SecureMigration.migrate(old, new) as SecureMigration.Result.Success
        assertEquals(2, r1.migratedKeys.size)
        val r2 = SecureMigration.migrate(old, new) as SecureMigration.Result.Success
        assertTrue(r2.migratedKeys.isEmpty()) // 第二次无新增
        assertEquals("1", new.get("a"))
    }

    @Test
    fun `migrate failure rolls back partial writes and keeps old`() {
        val old = MemStore().apply { map["k1"] = "v1"; map["k2"] = "v2"; map["k3"] = "v3" }
        val new = MemStore().apply { failOnPut = "k2" }
        val r = SecureMigration.migrate(old, new)
        assertTrue(r is SecureMigration.Result.Failure)
        // 本次部分写入已清理
        assertTrue(new.map.isEmpty())
        // 旧数据原样保留
        assertEquals("v1", old.get("k1"))
        assertEquals("v3", old.get("k3"))
    }

    @Test
    fun `migrate with empty old store succeeds`() {
        val r = SecureMigration.migrate(MemStore(), MemStore())
        assertTrue(r is SecureMigration.Result.Success)
        assertTrue((r as SecureMigration.Result.Success).migratedKeys.isEmpty())
    }

    @Test
    fun `TinkAeadStore round-trip with fake Aead`() {
        val store = TinkAeadStore(FakeAead(), MemPrefs(), b64 = JvmB64)
        store.put("secret", "s3cr3t 值")
        assertEquals("s3cr3t 值", store.get("secret"))
        assertEquals(setOf("secret"), store.allKeys())
        store.remove("secret")
        assertNull(store.get("secret"))
        assertTrue(store.allKeys().isEmpty())
    }

    @Test
    fun `TinkAeadStore isolates keys by associated data`() {
        val prefs = MemPrefs()
        val store = TinkAeadStore(FakeAead(), prefs, b64 = JvmB64)
        store.put("a", "same-value")
        store.put("b", "same-value")
        // 底层密文不同（associatedData 取 key 名）
        val rawA = prefs.map["tinkv1_a"]!!
        val rawB = prefs.map["tinkv1_b"]!!
        assertNotEquals(rawA, rawB)
        assertEquals("same-value", store.get("a"))
        assertEquals("same-value", store.get("b"))
    }
}
