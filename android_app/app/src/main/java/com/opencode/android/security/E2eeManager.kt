package com.opencode.android.security

import android.util.Base64
import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.util.AppLog

/**
 * v4.1: E2EE 会话管理（mobile 侧）。
 *
 * - 密钥对：X25519，私钥存加密存储（Tink/Legacy 后端，fail-closed），公钥可公开。
 * - 对端公钥：按 desktop device_id 存；配对成功（pair_success 带 e2ee_pubkey）时写入。
 * - 加解密：mobile→desktop 用 m2d 密钥，desktop→mobile 用 d2m 密钥（方向隔离）。
 * - 全部经 [FeatureFlags.ENABLE_E2EE] 门控；任一条件不满足则返回 null，调用方走明文。
 */
class E2eeManager(private val prefs: PreferencesManager) {

    /** E2EE 是否可用（运行时开关开 + 安全存储可用）。 */
    fun isAvailable(): Boolean =
        prefs.isE2eeEnabled && prefs.isSecureStorageAvailable

    /** 本机公钥（base64）；不存在则生成并持久化私钥。 */
    fun ownPublicKeyB64(): String? {
        if (!isAvailable()) return null
        return try {
            var priv = prefs.getE2eePrivateKey()
            if (priv.isNullOrEmpty()) {
                val kp = E2eeCrypto.generateKeypair()
                prefs.setE2eePrivateKey(kp.privateKeyB64)
                priv = kp.privateKeyB64
            }
            // 由私钥推导公钥（避免存两份）
            val pub = com.google.crypto.tink.subtle.X25519.publicFromPrivate(
                Base64.decode(priv, Base64.NO_WRAP)
            )
            Base64.encodeToString(pub, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.w("E2EE", "ownPublicKeyB64 失败: ${e.message}")
            null
        }
    }

    /** 保存对端（desktop）公钥。 */
    fun storePeerPubkey(deviceId: String, pubkeyB64: String) {
        if (!isAvailable() || deviceId.isEmpty() || pubkeyB64.isEmpty()) return
        try {
            prefs.setE2eePeerPubkey(deviceId, pubkeyB64)
            AppLog.i("E2EE", "已保存 $deviceId 的公钥")
        } catch (e: Exception) {
            AppLog.w("E2EE", "保存对端公钥失败: ${e.message}")
        }
    }

    /** 该 desktop 是否已协商 E2EE（有对端公钥）。 */
    fun hasPeerKey(deviceId: String): Boolean =
        isAvailable() && !prefs.getE2eePeerPubkey(deviceId).isNullOrEmpty()

    /** 加密发往 desktop 的载荷明文（通常是 payload JSON）。失败返回 null → 调用方走明文。 */
    fun encryptForDesktop(
        plaintext: String,
        desktopDeviceId: String,
        ownDeviceId: String,
        sessionId: String
    ): String? {
        if (!isAvailable()) return null
        return try {
            val priv = prefs.getE2eePrivateKey() ?: return null
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId) ?: return null
            val keys = E2eeCrypto.deriveMessageKeys(priv, peerPub)
            E2eeCrypto.encrypt(plaintext, keys.m2d, ownDeviceId, sessionId)
        } catch (e: Exception) {
            AppLog.w("E2EE", "加密失败，降级明文: ${e.message}")
            null
        }
    }

    /** 解密来自 desktop 的载荷。失败返回 null（调用方按错误处理，不静默吞掉）。 */
    fun decryptFromDesktop(
        payloadB64: String,
        desktopDeviceId: String,
        sessionId: String
    ): String? {
        if (!isAvailable()) return null
        return try {
            val priv = prefs.getE2eePrivateKey() ?: return null
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId) ?: return null
            val keys = E2eeCrypto.deriveMessageKeys(priv, peerPub)
            // AAD sender 为 desktop（加密方）
            E2eeCrypto.decrypt(payloadB64, keys.d2m, desktopDeviceId, sessionId)
        } catch (e: Exception) {
            AppLog.w("E2EE", "解密失败: ${e.message}")
            null
        }
    }

    /** 撤销设备时清理其公钥。 */
    fun removePeer(deviceId: String) {
        try { prefs.removeE2eePeerPubkey(deviceId) } catch (_: Exception) { }
    }
}
