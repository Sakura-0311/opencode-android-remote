package com.opencode.android.security

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * v4.6.0: E2EE 序号防重放（m2d/d2m 独立计数，落盘持久化）。
 */
class E2eeSeqTest {

    class FakePrefs : E2eePrefs {
        override var isE2eeEnabled: Boolean = true
        override val isSecureStorageAvailable: Boolean = true
        override var secretIsMaster: Boolean = false
        private var priv: String? = null
        private val peers = mutableMapOf<String, String>()
        private var ownRelayId: String? = null
        private val seqs = mutableMapOf<String, Long>()
        override fun getE2eePrivateKey(): String? = priv
        override fun setE2eePrivateKey(b64: String) { priv = b64 }
        override fun getE2eePeerPubkey(deviceId: String): String? = peers[deviceId]
        override fun setE2eePeerPubkey(deviceId: String, b64: String) { peers[deviceId] = b64 }
        override fun removeE2eePeerPubkey(deviceId: String) { peers.remove(deviceId) }
        override fun getSecret(): String = ""
        override fun getE2eeOwnRelayDeviceId(): String? = ownRelayId
        override fun setE2eeOwnRelayDeviceId(id: String) { ownRelayId = id }
        override fun getE2eeSeq(peerId: String, direction: String): Long =
            seqs["$peerId/$direction"] ?: 0L
        override fun setE2eeSeq(peerId: String, direction: String, seq: Long) {
            seqs["$peerId/$direction"] = seq
        }
    }

    private lateinit var prefs: FakePrefs
    private lateinit var mgr: E2eeManager

    @Before
    fun setup() {
        E2eeCrypto.b64Encode = { b -> java.util.Base64.getEncoder().encodeToString(b) }
        E2eeCrypto.b64Decode = { s -> java.util.Base64.getDecoder().decode(s) }
        prefs = FakePrefs()
        // 手机侧密钥对 + desktop 对端公钥（向量里的固定密钥）
        prefs.setE2eePrivateKey("8Fyi3xKF80+ciqcxS+HcVhL1VU13wUh7A9E0/UnWA2M=")
        prefs.setE2eePeerPubkey("desk-1", "PMCcE/zOHzdKkMMwky6VRVm/Fw+P7TAqs5V3uOuGwkA=")
        prefs.setE2eeOwnRelayDeviceId("mob-relay-1")
        mgr = E2eeManager(prefs)
    }

    @Test
    fun `m2d seq increments and inner format is v2 json`() {
        val r1 = mgr.encryptInnerForDesktop(
            JSONObject().apply {
                put("action", "send_prompt")
                put("payload", JSONObject().put("prompt", "hi"))
            }, "desk-1", "s1")
        assertTrue(r1 is E2eeManager.PayloadResult.Encrypted)
        val inner1 = JSONObject(decryptWithDesktopKey((r1 as E2eeManager.PayloadResult.Encrypted).b64, "s1"))
        assertEquals(1L, inner1.getLong("seq"))
        assertEquals("send_prompt", inner1.getString("action"))

        val r2 = mgr.encryptInnerForDesktop(
            JSONObject().apply {
                put("action", "send_prompt")
                put("payload", JSONObject().put("prompt", "hi2"))
            }, "desk-1", "s1")
        val inner2 = JSONObject(decryptWithDesktopKey((r2 as E2eeManager.PayloadResult.Encrypted).b64, "s1"))
        assertEquals(2L, inner2.getLong("seq"))
    }

    @Test
    fun `d2m replay is rejected`() {
        // 用 desktop 视角加密一条 d2m，手机解密一次成功、重放被拒
        val deskKeys = E2eeCrypto.deriveMessageKeys(
            "eMigLnSO89VoP40xZGOYOiCOMfo+RNM1J7Dh1JtD8V8=",
            "oWNd5uVwLuUpDTeGDW6WwiCUbGri2f4DTQ7FWwDQzzU=")
        val inner = JSONObject().apply {
            put("type", "stream_chunk")
            put("chunk", "hi")
            put("seq", 1)
        }
        val b64 = E2eeCrypto.encrypt(inner.toString(), deskKeys.d2m, "desk-1", "s1")
        assertNotNull(mgr.decryptFromDesktop(b64, "desk-1", "s1"))
        // 重放同一条 -> null
        assertNull(mgr.decryptFromDesktop(b64, "desk-1", "s1"))
    }

    @Test
    fun `encrypt fails closed without relay device id`() {
        prefs.setE2eeOwnRelayDeviceId("")
        val r = mgr.encryptInnerForDesktop(
            JSONObject().apply { put("action", "send_prompt"); put("payload", JSONObject()) },
            "desk-1", "s1")
        assertTrue(r is E2eeManager.PayloadResult.Failed)
    }

    /** 用 desktop 视角解密 m2d（测试辅助）。 */
    private fun decryptWithDesktopKey(b64: String, sessionId: String): String {
        val deskKeys = E2eeCrypto.deriveMessageKeys(
            "eMigLnSO89VoP40xZGOYOiCOMfo+RNM1J7Dh1JtD8V8=",
            "oWNd5uVwLuUpDTeGDW6WwiCUbGri2f4DTQ7FWwDQzzU=")
        // AAD sender 为手机 relay device_id
        return E2eeCrypto.decrypt(b64, deskKeys.m2d, "mob-relay-1", sessionId)
    }
}
