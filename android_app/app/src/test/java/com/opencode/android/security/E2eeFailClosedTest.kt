package com.opencode.android.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * v4.3 M-1: E2EE fail-closed 负向测试。
 * E2EE 开启时任何加密失败必须返回 Failed（调用方拒绝发送），绝不能是 Plaintext（会静默回退明文）。
 */
class E2eeFailClosedTest {

    /** 内存假实现，不依赖 Android 框架。 */
    class FakePrefs : E2eePrefs {
        override var isE2eeEnabled: Boolean = false
        override val isSecureStorageAvailable: Boolean = true
        override var secretIsMaster: Boolean = false
        var pairingSecret: String = ""
        private var priv: String? = null
        private val peers = mutableMapOf<String, String>()
        override fun getE2eePrivateKey(): String? = priv
        override fun setE2eePrivateKey(b64: String) { priv = b64 }
        override fun getE2eePeerPubkey(deviceId: String): String? = peers[deviceId]
        override fun setE2eePeerPubkey(deviceId: String, b64: String) { peers[deviceId] = b64 }
        override fun removeE2eePeerPubkey(deviceId: String) { peers.remove(deviceId) }
        override fun getSecret(): String = pairingSecret
    }

    @Before
    fun setup() {
        E2eeCrypto.b64Encode = { b -> java.util.Base64.getEncoder().encodeToString(b) }
        E2eeCrypto.b64Decode = { s -> java.util.Base64.getDecoder().decode(s) }
    }

    @Test
    fun `E2EE 开启时加密失败必须拒绝发送而非明文`() {
        val prefs = FakePrefs()
        val mgr = E2eeManager(prefs)
        prefs.isE2eeEnabled = true
        prefs.setE2eePrivateKey(E2eeCrypto.generateKeypair().privateKeyB64)
        prefs.setE2eePeerPubkey("desktop-1", "")        // 制造失败条件：对端公钥缺失
        val r = mgr.encryptForDesktop("secret payload", "desktop-1", "mob-1", "ses_1")
        assertTrue("加密失败必须返回 Failed", r is E2eeManager.PayloadResult.Failed)
        assertTrue("绝不能是 Plaintext", r !is E2eeManager.PayloadResult.Plaintext)
    }

    @Test
    fun `E2EE 开启但本机私钥缺失返回 Failed`() {
        val prefs = FakePrefs()
        val mgr = E2eeManager(prefs)
        prefs.isE2eeEnabled = true
        // 不设私钥
        val r = mgr.encryptForDesktop("secret", "desktop-1", "mob-1", "ses_1")
        assertTrue(r is E2eeManager.PayloadResult.Failed)
    }

    @Test
    fun `E2EE 关闭时返回 Plaintext（用户知情的明文）`() {
        val prefs = FakePrefs()
        val mgr = E2eeManager(prefs)
        prefs.isE2eeEnabled = false
        val r = mgr.encryptForDesktop("plain", "desktop-1", "mob-1", "ses_1")
        assertTrue(r is E2eeManager.PayloadResult.Plaintext)
    }

    @Test
    fun `E2EE 开启且密钥齐全时加密成功`() {
        val prefs = FakePrefs()
        val mgr = E2eeManager(prefs)
        prefs.isE2eeEnabled = true
        val mobile = E2eeCrypto.generateKeypair()
        val desktop = E2eeCrypto.generateKeypair()
        prefs.setE2eePrivateKey(mobile.privateKeyB64)
        prefs.setE2eePeerPubkey("desktop-1", desktop.publicKeyB64)
        val r = mgr.encryptForDesktop("secret payload", "desktop-1", "mob-1", "ses_1")
        assertTrue("应加密成功", r is E2eeManager.PayloadResult.Encrypted)
        // 对端能解开（方向 m2d，AAD sender 为 mobile）
        val dKeys = E2eeCrypto.deriveMessageKeys(desktop.privateKeyB64, mobile.publicKeyB64)
        val plain = E2eeCrypto.decrypt(
            (r as E2eeManager.PayloadResult.Encrypted).b64, dKeys.m2d, "mob-1", "ses_1")
        assertEquals("secret payload", plain)
    }
}

/**
 * v4.3 M-2: 公钥 HMAC 绑定跨语言一致性（Python desktop_agent/modules/e2ee.py::sign_pubkey）。
 * 向量由 Python 生成：sign_pubkey("test-room-secret", "desktop-abc", "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWQ==")
 */
class E2eePubkeySigTest {
    @Test
    fun `hmacPubkeySig 与 Python 实现一致`() {
        val sig = E2eeCrypto.hmacPubkeySig(
            "test-room-secret", "desktop-abc", "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWQ==")
        assertEquals(
            "b0315f19a9d584dfb18d9036444e2f136d7105e5b6c94ccb0565966315cfcd59", sig)
    }

    @Test
    fun `storePeerPubkey 签名无效时拒绝保存`() {
        val prefs = E2eeFailClosedTest.FakePrefs()
        prefs.isE2eeEnabled = true
        prefs.secretIsMaster = true
        // FakePrefs 继承 E2eePrefs，需要 getSecret；这里直接用匿名实现
        prefs.pairingSecret = "test-room-secret"
        val mgr = E2eeManager(prefs)
        val ok = mgr.storePeerPubkey("desktop-abc",
            "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWQ==", "deadbeef".repeat(8))
        assertFalse("签名无效必须拒绝保存", ok)
    }

    @Test
    fun `storePeerPubkey 签名有效时保存`() {
        val prefs = E2eeFailClosedTest.FakePrefs()
        prefs.isE2eeEnabled = true
        prefs.secretIsMaster = true
        prefs.pairingSecret = "test-room-secret"
        val mgr = E2eeManager(prefs)
        val ok = mgr.storePeerPubkey("desktop-abc",
            "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWQ==",
            "b0315f19a9d584dfb18d9036444e2f136d7105e5b6c94ccb0565966315cfcd59")
        assertTrue("签名有效应保存", ok)
        assertEquals("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWQ==",
            prefs.getE2eePeerPubkey("desktop-abc"))
    }
}
