package com.opencode.android.network

import org.json.JSONObject

/**
 * WebSocket 消息的纯解析阶段：JSON 解析 + E2EE 解密合并。
 *
 * 不碰任何共享状态（decrypt 由调用方注入，正常实现只读 prefs + 纯 crypto），
 * 可在单线程解析器上执行；无 Android 依赖，可 JVM 单测。
 * 日志由调用方根据 Outcome 打（AppLog 内部同步，线程安全）。
 */
class RelayMessageParser(
    private val decrypt: ((payloadB64: String, srcId: String, sessId: String) -> String?)? = null,
) {
    sealed interface Outcome {
        /** 解析成功；decryptedFrom 非空表示经过 E2EE 解密（值为来源 deviceId）。 */
        data class Ok(val json: JSONObject, val decryptedFrom: String? = null) : Outcome
        /** 外层 JSON 解析失败，或解析阶段出现未预期异常。 */
        data class BadJson(val error: String) : Outcome
        /** E2EE 解密失败（或未配对）。 */
        data object DecryptFailed : Outcome
        /** 解密成功但内层不是合法 JSON。 */
        data class BadInnerJson(val error: String) : Outcome
    }

    fun parseAndDecrypt(jsonText: String): Outcome {
        val json: JSONObject
        try {
            json = JSONObject(jsonText)
        } catch (e: Exception) {
            return Outcome.BadJson(e.message ?: "JSON 解析失败")
        }
        // E2EE：解密内容载荷（路由字段保持明文），解密出的字段合并进外层
        if (json.optBoolean("e2ee", false) && json.has("encrypted_payload")) {
            val srcId = json.optString("source_device_id", "")
            val sessId = json.optString("session_id", "default")
            val decrypted = decrypt?.invoke(json.optString("encrypted_payload", ""), srcId, sessId)
            if (decrypted != null) {
                try {
                    val inner = JSONObject(decrypted)
                    for (key in inner.keys()) json.put(key, inner.get(key))
                } catch (e: Exception) {
                    return Outcome.BadInnerJson(e.message ?: "内层 JSON 解析失败")
                }
                return Outcome.Ok(json, decryptedFrom = srcId)
            }
            return Outcome.DecryptFailed
        }
        return Outcome.Ok(json)
    }
}
