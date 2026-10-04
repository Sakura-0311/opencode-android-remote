package com.opencode.android.security

import android.util.Base64
import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519

/**
 * v4.1: E2EE 端到端加密（mobile ↔ desktop，relay 盲转发）。
 * 规范见 docs/E2EE_WIRE_v1.md。
 *
 * - X25519 ECDH 协商；HKDF-SHA256 派生方向隔离的消息密钥；
 *   ChaCha20-Poly1305 加密内容载荷（nonce 12B 随机）。
 * - 路由元数据（type/session_id/seq/device_id）保持明文，relay 可正常路由/缓冲。
 * - 全部经 [FeatureFlags.ENABLE_E2EE] 门控，默认关闭。
 */
object E2eeCrypto {

    private const val HKDF_HASH = "HMACSHA256"
    private const val KEY_LEN = 32
    private const val NONCE_LEN = 12
    private const val INFO_M2D = "opencode-remote-e2ee-v1-m2d"
    private const val INFO_D2M = "opencode-remote-e2ee-v1-d2m"

    /**
     * v4.1: Base64 编解解耦（测试替身）。
     * 生产默认 android.util.Base64；单测注入 java.util.Base64。
     */
    var b64Encode: (ByteArray) -> String =
        { b -> android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) }
    var b64Decode: (String) -> ByteArray =
        { s -> android.util.Base64.decode(s, android.util.Base64.NO_WRAP) }

    data class Keypair(val privateKeyB64: String, val publicKeyB64: String)
    data class MessageKeys(val m2d: ByteArray, val d2m: ByteArray)

    fun generateKeypair(): Keypair {
        val priv = X25519.generatePrivateKey()
        val pub = X25519.publicFromPrivate(priv)
        return Keypair(b64(priv), b64(pub))
    }

    /** 由己方私钥 + 对方公钥派生方向隔离的消息密钥。 */
    fun deriveMessageKeys(ownPrivateKeyB64: String, peerPublicKeyB64: String): MessageKeys {
        val shared = X25519.computeSharedSecret(unb64(ownPrivateKeyB64), unb64(peerPublicKeyB64))
        // salt 用显式 32 零字节：与 Python cryptography 侧严格一致（避免空 salt 边缘行为差异）
        val salt = ByteArray(32)
        val m2d = Hkdf.computeHkdf(HKDF_HASH, shared, salt, INFO_M2D.toByteArray(), KEY_LEN)
        val d2m = Hkdf.computeHkdf(HKDF_HASH, shared, salt, INFO_D2M.toByteArray(), KEY_LEN)
        return MessageKeys(m2d, d2m)
    }

    /**
     * 加密。plaintext 为待保护内容的 UTF-8 字符串（通常是一段 JSON）。
     * Tink 的 ChaCha20Poly1305.encrypt 自带 12B 随机 nonce，返回 nonce||密文；
     * 线格式 base64(nonce||ct) 与规范一致。
     * @param senderDeviceId 发送端 device_id，用于 AAD 绑定
     */
    fun encrypt(plaintext: String, key: ByteArray, senderDeviceId: String, sessionId: String): String {
        val aad = aad(senderDeviceId, sessionId)
        val out = ChaCha20Poly1305(key).encrypt(plaintext.toByteArray(Charsets.UTF_8), aad)
        return b64(out)
    }

    /** 解密 [encrypt] 产生的 base64 载荷，失败抛 GeneralSecurityException。 */
    fun decrypt(payloadB64: String, key: ByteArray, senderDeviceId: String, sessionId: String): String {
        val raw = unb64(payloadB64)
        require(raw.size > NONCE_LEN) { "e2ee payload too short" }
        val aad = aad(senderDeviceId, sessionId)
        val pt = ChaCha20Poly1305(key).decrypt(raw, aad)
        return pt.toString(Charsets.UTF_8)
    }

    private fun aad(senderDeviceId: String, sessionId: String): ByteArray =
        "$senderDeviceId:$sessionId".toByteArray(Charsets.UTF_8)

    private fun b64(b: ByteArray): String = b64Encode(b)
    private fun unb64(s: String): ByteArray = b64Decode(s)
}
