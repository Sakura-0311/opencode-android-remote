package com.opencode.android.data.model

import com.opencode.android.data.AppMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * v2.5: 多连接配置 profile。
 * - 密钥不进 JSON：secret / cloudApiKey 按 profile id 存加密存储（secretRef）
 * - JSON 只存非敏感字段，导出配置时可安全分享
 */
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mode: AppMode = AppMode.DESKTOP_RELAY,
    val relayUrl: String = "",
    val accountId: String = "",
    val cloudUrl: String = "",
    val cloudWorkspace: String = "/workspace"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mode", mode.name)
        .put("relayUrl", relayUrl)
        .put("accountId", accountId)
        .put("cloudUrl", cloudUrl)
        .put("cloudWorkspace", cloudWorkspace)

    companion object {
        fun fromJson(o: JSONObject): ConnectionProfile = ConnectionProfile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { "未命名" },
            mode = runCatching { AppMode.valueOf(o.optString("mode")) }.getOrDefault(AppMode.DESKTOP_RELAY),
            relayUrl = o.optString("relayUrl"),
            accountId = o.optString("accountId"),
            cloudUrl = o.optString("cloudUrl"),
            cloudWorkspace = o.optString("cloudWorkspace", "/workspace")
        )

        fun listFromJson(json: String): List<ConnectionProfile> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }

        fun listToJson(list: List<ConnectionProfile>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
