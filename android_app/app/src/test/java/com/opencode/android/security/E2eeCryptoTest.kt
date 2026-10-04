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

    /**
     * v4.1: 跨语言互操作——Python（cryptography）生成的测试向量，
     * Kotlin（Tink）必须能解开。向量生成脚本见仓库（固定密钥/nonce，仅测试用）。
     * 覆盖：X25519 ECDH、HKDF（salt=32零、info）、ChaCha20Poly1305、AAD、base64。
     */
    @Test
    fun `interop - decrypt Python-produced ciphertext`() {
        val mobilePrivB64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        val desktopPubB64 = "WGmv9FBUlzLLqu1eXfmzCm2jHLDldCutWtShp2jxpns="
        val sender = "desk-test-1"
        val session = "sess-test-1"
        val expectedPlaintext = "{\"chunk\":\"Hello E2EE interop\"}"
        val payloadB64 = "AAECAwQFBgcICQoLYDWhy5gZ8uf43fjipjFeEuuLE4dwB6AlJrXjEtfMNHpxE5GhZAHuFi08GK0/nw=="

        // mobile 侧：由己方私钥 + desktop 公钥派生 d2m（desktop→mobile 方向）
        val keys = E2eeCrypto.deriveMessageKeys(mobilePrivB64, desktopPubB64)
        val recovered = E2eeCrypto.decrypt(payloadB64, keys.d2m, sender, session)
        assertEquals(expectedPlaintext, recovered)
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
