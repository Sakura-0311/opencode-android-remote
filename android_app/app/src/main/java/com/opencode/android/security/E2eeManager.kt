package com.opencode.android.security

import android.util.Base64
import com.opencode.android.coordinator.PairingE2ee
import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.util.AppLog
import org.json.JSONObject

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
    // v4.6.0: 本机在 relay 侧的 device_id（配对时 relay 分配），E2EE AAD sender 用它
    // （与 desktop 侧保存对端公钥的 peer id 一致），非敏感，明文存储
    fun getE2eeOwnRelayDeviceId(): String?
    fun setE2eeOwnRelayDeviceId(id: String)
    // v4.6.0: E2EE 序号计数器（防重放），按对端+方向独立；非敏感，明文存储
    fun getE2eeSeq(peerId: String, direction: String): Long
    fun setE2eeSeq(peerId: String, direction: String, seq: Long)
    /** 当前配对 secret（手动配对=房间主 secret，扫码配对=device_secret）。 */
    fun getSecret(): String
    /** 存储的 secret 是否为房间主 secret（手动配对时 true，扫码配对时 false）。 */
    var secretIsMaster: Boolean

    // v5.0.3 (A-1): 最近一次成功绑定公钥的对端 deviceId（非敏感，明文存储）。
    // 多桌面路由关闭时 relay 不下发 desktop_list，客户端没有别的途径知道该把
    // 密文加密给谁——没有这个回退，A-1 的加密根本不会触发，E2EE 等于没开。
    // 默认实现保证既有假实现（单测）无需改动。
    fun getE2eeLastPeerId(): String? = null
    fun setE2eeLastPeerId(deviceId: String) {}
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
            // v5.0.3 (A-1): 记住这个对端——多桌面路由关闭时没有 desktop_list，
            // 出站加密只能靠它确定目标
            prefs.setE2eeLastPeerId(deviceId)
            AppLog.i("E2EE", "已保存 $deviceId 的公钥")
            true
        } catch (e: Exception) {
            AppLog.w("E2EE", "保存对端公钥失败: ${e.message}")
            false
        }
    }

    /** v5.0.3 (A-1): 最近一次协商成功的对端 deviceId；没有则 null（走明文）。 */
    override fun lastNegotiatedPeerId(): String? =
        prefs.getE2eeLastPeerId()?.takeIf { it.isNotBlank() }

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
     * v5.0.2: 失败原因用**错误码**表达，由持有 Context 的 UI 层映射到字符串资源。
     * 此前这里是中文字符串常量，导致「10 语言」下这几条提示永远是中文；
     * 但 E2eeManager 是安全类、不持有 Context，所以把文案上移到 UI 才是正解。
     */
    enum class Failure {
        /** 本机 E2EE 私钥缺失 */
        NO_PRIVATE_KEY,
        /** 尚未与该 desktop 协商公钥；detail = desktopDeviceId */
        NO_PEER_KEY,
        /** 本机 relay device_id 缺失，需重新配对 */
        NO_RELAY_DEVICE_ID,
        /** 加解密抛异常；detail = 异常信息 */
        CRYPTO_ERROR
    }

    sealed interface PayloadResult {
        object Plaintext : PayloadResult
        data class Encrypted(val b64: String) : PayloadResult
        /** E2EE 已开启但加密失败 —— 调用方必须拒绝发送，绝不回退明文 */
        data class Failed(val failure: Failure, val detail: String? = null) : PayloadResult
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
                ?: return PayloadResult.Failed(Failure.NO_PRIVATE_KEY)
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId)
            if (peerPub.isNullOrBlank()) {
                return PayloadResult.Failed(Failure.NO_PEER_KEY, desktopDeviceId)
            }
            val keys = E2eeCrypto.deriveMessageKeys(priv, peerPub)
            PayloadResult.Encrypted(E2eeCrypto.encrypt(plaintext, keys.m2d, ownDeviceId, sessionId))
        } catch (e: Exception) {
            AppLog.e("E2EE", "加密失败（fail-closed，不回退明文）: ${e.message}")
            PayloadResult.Failed(Failure.CRYPTO_ERROR, e.message)
        }
    }

    /** 解密来自 desktop 的载荷。失败返回 null（调用方按错误处理，不静默吞掉）。
     * v4.6.0: 内层为 JSON {"type":..., ...字段..., "seq":N}，校验序号防重放。 */
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
            val innerStr = E2eeCrypto.decrypt(payloadB64, keys.d2m, desktopDeviceId, sessionId)
            val inner = JSONObject(innerStr)
            val seq = inner.optLong("seq", -1)
            val last = prefs.getE2eeSeq(desktopDeviceId, "d2m")
            if (seq <= 0 || seq <= last) {
                AppLog.w("E2EE", "d2m 序号非法/重放（seq=$seq, last=$last），丢弃")
                return null
            }
            prefs.setE2eeSeq(desktopDeviceId, "d2m", seq)
            innerStr
        } catch (e: Exception) {
            AppLog.w("E2EE", "解密失败: ${e.message}")
            null
        }
    }

    /**
     * v4.6.0: 加密发往 desktop 的内层 JSON。调用方构造 {"action":..., "payload":...}，
     * 方法写入单调递增 seq 后加密。AAD sender 用本机 relay device_id
     * （desktop 侧以同一 id 存对端公钥并做 AAD，两端一致）。
     */
    fun encryptInnerForDesktop(
        inner: JSONObject,
        desktopDeviceId: String,
        sessionId: String
    ): PayloadResult {
        if (!isAvailable()) return PayloadResult.Plaintext
        return try {
            val priv = prefs.getE2eePrivateKey()
                ?: return PayloadResult.Failed(Failure.NO_PRIVATE_KEY)
            val peerPub = prefs.getE2eePeerPubkey(desktopDeviceId)
            if (peerPub.isNullOrBlank()) {
                return PayloadResult.Failed(Failure.NO_PEER_KEY, desktopDeviceId)
            }
            val ownId = prefs.getE2eeOwnRelayDeviceId()?.takeIf { it.isNotBlank() }
                ?: return PayloadResult.Failed(Failure.NO_RELAY_DEVICE_ID)
            val seq = prefs.getE2eeSeq(desktopDeviceId, "m2d") + 1
            inner.put("seq", seq)
            val keys = E2eeCrypto.deriveMessageKeys(priv, peerPub)
            val enc = E2eeCrypto.encrypt(inner.toString(), keys.m2d, ownId, sessionId)
            prefs.setE2eeSeq(desktopDeviceId, "m2d", seq)
            PayloadResult.Encrypted(enc)
        } catch (e: Exception) {
            AppLog.e("E2EE", "加密失败（fail-closed，不回退明文）: ${e.message}")
            PayloadResult.Failed(Failure.CRYPTO_ERROR, e.message)
        }
    }

    /** 撤销设备时清理其公钥。 */
    fun removePeer(deviceId: String) {
        try {
            prefs.removeE2eePeerPubkey(deviceId)
        } catch (e: Exception) {
            // v5.0.2: 不再静默吞掉——撤销失败意味着旧公钥仍在，值得留痕
            AppLog.e("E2EE", "清理对端公钥失败 deviceId=$deviceId: ${e.message}")
        }
    }
}
