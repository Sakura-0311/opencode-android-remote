package com.opencode.android.util

import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.data.model.ConnectionProfile
import org.json.JSONObject

/**
 * v2.5: 配置导入 / 导出。
 * - 导出 JSON 默认不含任何密钥（profile JSON 本就不含 secretRef）。
 * - 导入后密钥为空，调用方必须提示用户重新配对。
 */
object ConfigImportExport {

    private const val EXPORT_VERSION = 1

    /** 导出当前全部 profiles（不含密钥）为 JSON 字符串 */
    fun exportJson(prefs: PreferencesManager): String {
        val obj = JSONObject()
            .put("version", EXPORT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("activeProfileId", prefs.getActiveProfileId())
        val arr = org.json.JSONArray()
        prefs.getProfiles().forEach { arr.put(it.toJson()) }
        obj.put("profiles", arr)
        return obj.toString(2)
    }

    /**
     * 导入配置 JSON。成功返回导入的 profile 列表；
     * 失败返回 null（原配置不动）。
     * 注意：导入的 profile 没有密钥，需重新配对。
     */
    fun importJson(prefs: PreferencesManager, json: String): List<ConnectionProfile>? {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt("version", 0) != EXPORT_VERSION) return null
            val arr = obj.optJSONArray("profiles") ?: return null
            if (arr.length() == 0) return null
            val profiles = (0 until arr.length())
                .map { ConnectionProfile.fromJson(arr.getJSONObject(it)) }
                .filter { it.name.isNotBlank() }
            if (profiles.isEmpty()) return null
            // 名称去重，避免与现有 profile 混淆
            val usedNames = prefs.getProfiles().map { it.name }.toMutableSet()
            val deduped = profiles.map {
                var name = it.name
                var i = 2
                while (name in usedNames) { name = "${it.name}($i)"; i++ }
                usedNames.add(name)
                it.copy(name = name)
            }
            prefs.saveProfiles(prefs.getProfiles() + deduped)
            deduped
        } catch (_: Exception) {
            null
        }
    }
}
