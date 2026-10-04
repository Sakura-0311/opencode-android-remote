package com.opencode.android.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * v4.1: E2EE 加解密单测（纯 Tink subtle + JVM Base64 替身，不依赖 Android 框架）。
 */
class E2eeCryptoTest {

    @Before
    fun setup() {
        // JVM 环境没有 android.util.Base64，注入替身
        E2eeCrypto.b64Encode = { b -> java.util.Base64.getEncoder().encodeToString(b) }
        E2eeCrypto.b64Decode = { s -> java.util.Base64.getDecoder().decode(s) }
    }

    @Test
    fun `keypair generates 32-byte keys`() {
        val kp = E2eeCrypto.generateKeypair()
        val priv = java.util.Base64.getDecoder().decode(kp.privateKeyB64)
        val pub = java.util.Base64.getDecoder().decode(kp.publicKeyB64)
        assertEquals(32, priv.size)
        assertEquals(32, pub.size)
    }

    @Test
    fun `both sides derive equal shared message keys`() {
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        val mKeys = E2eeCrypto.deriveMessageKeys(mobile.privateKeyB64, desktop.publicKeyB64)
        val dKeys = E2eeCrypto.deriveMessageKeys(desktop.privateKeyB64, mobile.publicKeyB64)
        assertArrayEquals(mKeys.m2d, dKeys.m2d)
        assertArrayEquals(mKeys.d2m, dKeys.d2m)
    }

    @Test
    fun `direction isolation - m2d key differs from d2m key`() {
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        val keys = E2eeCrypto.deriveMessageKeys(mobile.privateKeyB64, desktop.publicKeyB64)
        assertFalse(keys.m2d.contentEquals(keys.d2m))
    }

    @Test
    fun `encrypt then decrypt roundtrip`() {
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        val mKeys = E2eeCrypto.deriveMessageKeys(mobile.privateKeyB64, desktop.publicKeyB64)
        val dKeys = E2eeCrypto.deriveMessageKeys(desktop.privateKeyB64, mobile.publicKeyB64)

        val plaintext = """{"prompt":"hello e2ee"}"""
        // mobile→desktop：mobile 用 m2d 加密，desktop 用 m2d 解密（AAD sender=mobile）
        val payload = E2eeCrypto.encrypt(plaintext, mKeys.m2d, "mobile-1", "s1")
        val recovered = E2eeCrypto.decrypt(payload, dKeys.m2d, "mobile-1", "s1")
        assertEquals(plaintext, recovered)
    }

    @Test
    fun `cross-direction decrypt fails`() {
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        val mKeys = E2eeCrypto.deriveMessageKeys(mobile.privateKeyB64, desktop.publicKeyB64)
        val dKeys = E2eeCrypto.deriveMessageKeys(desktop.privateKeyB64, mobile.publicKeyB64)

        val payload = E2eeCrypto.encrypt("secret", mKeys.m2d, "mobile-1", "s1")
        // 用 d2m 解密 m2d 密文 → 必须失败
        try {
            E2eeCrypto.decrypt(payload, dKeys.d2m, "mobile-1", "s1")
            fail("跨方向解密应该失败")
        } catch (_: Exception) {
            // 预期
        }
    }

    @Test
    fun `tampered AAD fails decrypt`() {
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        val mKeys = E2eeCrypto.deriveMessageKeys(mobile.privateKeyB64, desktop.publicKeyB64)
        val dKeys = E2eeCrypto.deriveMessageKeys(desktop.privateKeyB64, mobile.publicKeyB64)

        val payload = E2eeCrypto.encrypt("secret", mKeys.m2d, "mobile-1", "s1")
        // 篡改 session（AAD 的一部分）→ 必须失败
        try {
            E2eeCrypto.decrypt(payload, dKeys.m2d, "mobile-1", "s2")
            fail("AAD 篡改后解密应该失败")
        } catch (_: Exception) {
            // 预期
        }
    }
}
