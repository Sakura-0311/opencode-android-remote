package com.opencode.android.security

import android.util.Base64
import com.opencode.android.coordinator.PairingE2ee
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
/**
 * v4.3 M-1: E2EE 密钥存储抽象——PreferencesManager 实现它，单测用内存假实现。
 * 只含 E2EE 需要的最窄接口。
 */
interface E2eePrefs {
    val isE2eeEnabled: Boolean
    val isSecureStorageAvailable: Boolean
    fun getE2eePrivateKey(): String?
    fun setE2eePrivateKey(b64: String)
    fun getE2eePeerPubkey(deviceId: String): String?
    fun setE2eePeerPubkey(deviceId: String, b64: String)
    fun removeE2eePeerPubkey(deviceId: String)
    /** 当前配对 secret（手动配对=房间主 secret，扫码配对=device_secret）。 */
    fun getSecret(): String
    /** 存储的 secret 是否为房间主 secret（手动配对时 true，扫码配对时 false）。 */
    var secretIsMaster: Boolean
}

class E2eeManager(private val prefs: E2eePrefs) : PairingE2ee {

    /** E2EE 是否可用（运行时开关开 + 安全存储可用）。 */
    fun isAvailable(): Boolean =
        prefs.isE2eeEnabled && prefs.isSecureStorageAvailable

    /** 本机公钥（base64）；不存在则生成并持久化私钥。 */
    override fun ownPublicKeyB64(): String? {
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
    /**
     * v4.3 M-2: 保存对端公钥前做 HMAC 绑定校验。
     * @return true=已保存；false=拒绝保存（签名无效，疑似中继篡改）
     */
    override fun storePeerPubkey(deviceId: String, pubkeyB64: String, sig: String): Boolean {
        if (!isAvailable() || deviceId.isEmpty() || pubkeyB64.isEmpty()) return false
        // 只有持有房间主 secret（手动配对）时才能校验；扫码配对用 device_secret，
        // relay 明文知道它，无法做不可伪造绑定，走 TOFU（威胁模型已声明）。
        if (prefs.secretIsMaster) {
            val secret = prefs.getSecret()
            if (sig.isEmpty()) {
                AppLog.w("E2EE", "对端未提供公钥认证签名（旧版本），TOFU 保存")
            } else {
                val expected = try {
                    E2eeCrypto.hmacPubkeySig(secret, deviceId, pubkeyB64)
                } catch (e: Exception) {
                    AppLog.e("E2EE", "签名计算失败: ${e.message}"); return false
                }
                if (!constantTimeEq(expected, sig)) {
                    AppLog.e("E2EE", "公钥签名校验失败，拒绝保存（疑似中继篡改）")
                    return false
                }
                AppLog.i("E2EE", "$deviceId 的公钥已通过 HMAC 绑定认证")
            }
        } else {
            AppLog.i("E2EE", "扫码配对：公钥走 TOFU 保存（无主 secret 可认证）")
        }
        return try {
            prefs.setE2eePeerPubkey(deviceId, pubkeyB64)
            AppLog.i("E2EE", "已保存 $deviceId 的公钥")
            true
        } catch (e: Exception) {
            AppLog.w("E2EE", "保存对端公钥失败: ${e.message}")
            false
        }
    }

    private fun constantTimeEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** 该 desktop 是否已协商 E2EE（有对端公钥）。 */
    override fun hasPeerKey(deviceId: String): Boolean =
        isAvailable() && !prefs.getE2eePeerPubkey(deviceId).isNullOrEmpty()

    /**
     * v4.3 M-1: 载荷加密结果三态——调用方不可能把「加密失败」误判成「未开启」。
     * - Plaintext: E2EE 未开启（用户知情），可走明文
     * - Encrypted: 加密成功
     * - Failed: E2EE 已开启但加密失败 —— 调用方必须拒绝发送，绝不回退明文
     */
    sealed interface PayloadResult {
        object Plaintext : PayloadResult
        data class Encrypted(val b64: String) : PayloadResult
        data class Failed(val reason: String) : PayloadResult
    }

    /** 加密发往 desktop 的载荷明文（通常是 payload JSON）。v4.3 起返回三态，不再返回可被误读的 null。 */
    fun encryptForDesktop(
        plaintext: String,
        desktopDeviceId: String,
        ownDeviceId: String,
        sessionId: String
    ): PayloadResult {
        if (!isAvailable()) return PayloadResult.Plaintext
        return try {
            val priv = prefs.getE2eePrivateKey()
                ?: return PayloadResult.Failed("本机 E2EE 私钥缺失")
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId)
            if (peerPub.isNullOrBlank()) return PayloadResult.Failed("尚未与 $desktopDeviceId 协商 E2EE 公钥")
            val keys = E2eeCrypto.deriveMessageKeys(priv, peerPub)
            PayloadResult.Encrypted(E2eeCrypto.encrypt(plaintext, keys.m2d, ownDeviceId, sessionId))
        } catch (e: Exception) {
            AppLog.e("E2EE", "加密失败（fail-closed，不回退明文）: ${e.message}")
            PayloadResult.Failed(e.message ?: "未知加密错误")
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
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId)
            if (peerPub.isNullOrBlank()) return null
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
