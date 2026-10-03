package com.opencode.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.SessionItem
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    // P0-3: 加密存储失败时禁止静默降级（fail-closed）。
    // securePrefs 为 null 表示加密不可用：敏感凭据（Secret / API Key / AccountId）
    // 拒绝读写；非敏感偏好仍可用普通存储。
    private val securePrefs: SharedPreferences? = try {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.e("PrefsManager", "P0-3: EncryptedSharedPreferences 初始化失败，敏感凭据将被拒绝保存", e)
        null
    }

    /** 加密存储是否可用；为 false 时禁止保存任何敏感凭据 */
    val isSecureStorageAvailable: Boolean get() = securePrefs != null

    // 非敏感偏好：加密可用时走加密存储，否则走普通存储（不含敏感数据）
    private val prefs: SharedPreferences =
        securePrefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // v2.3: 存储 schema 版本。只增不改 key；变更带幂等迁移。
    init {
        migrateIfNeeded()
    }

    companion object {
        private const val PREFS_NAME = "opencode_remote_prefs"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_SECRET = "secret"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_CLOUD_SERVER_URL = "cloud_server_url"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_WORKSPACE_PATH = "cloud_workspace_path"
        private const val KEY_SAVED_SESSIONS = "saved_sessions_json"
        private const val KEY_SAVED_TAGS = "saved_tags_json"
        // v1.6 P0 后台保活：任务状态持久化
        private const val KEY_TASK_STATUS = "task_status"
        private const val KEY_TASK_DETAIL = "task_status_detail"
        private const val KEY_TASK_SESSION = "task_session_id"

        private const val DEFAULT_RELAY_URL = ""
        private const val DEFAULT_CLOUD_URL = ""
        private const val DEFAULT_CLOUD_WORKSPACE = "/workspace"
    }

    fun getAppMode(): AppMode {
        val modeStr = prefs.getString(KEY_APP_MODE, AppMode.DESKTOP_RELAY.name) ?: AppMode.DESKTOP_RELAY.name
        return try {
            AppMode.valueOf(modeStr)
        } catch (e: Exception) {
            AppMode.DESKTOP_RELAY
        }
    }

    fun saveAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.name).apply()
    }

    fun getAccountId(): String {
        return securePrefs?.getString(KEY_ACCOUNT_ID, "") ?: ""
    }

    fun getSecret(): String {
        return securePrefs?.getString(KEY_SECRET, "") ?: ""
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    }

    /**
     * P0-3: 保存配对敏感凭据。加密存储不可用时返回 false，调用方必须提示用户且不得继续。
     */
    fun savePairingInfo(accountId: String, secret: String, relayUrl: String): Boolean {
        val sp = securePrefs ?: run {
            android.util.Log.e("PrefsManager", "P0-3: 拒绝保存配对凭据——加密存储不可用")
            return false
        }
        sp.edit()
            .putString(KEY_ACCOUNT_ID, accountId)
            .putString(KEY_SECRET, secret)
            .apply()
        // relayUrl 非敏感，可走普通偏好
        prefs.edit().putString(KEY_RELAY_URL, relayUrl).apply()
        return true
    }

    fun getCloudServerUrl(): String {
        return prefs.getString(KEY_CLOUD_SERVER_URL, DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
    }

    fun getCloudApiKey(): String {
        return securePrefs?.getString(KEY_CLOUD_API_KEY, "") ?: ""
    }

    fun getCloudWorkspacePath(): String {
        return prefs.getString(KEY_CLOUD_WORKSPACE_PATH, DEFAULT_CLOUD_WORKSPACE) ?: DEFAULT_CLOUD_WORKSPACE
    }

    /**
     * P0-3: 保存云端敏感凭据。加密存储不可用时返回 false，调用方必须提示用户且不得继续。
     */
    fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String): Boolean {
        val sp = securePrefs ?: run {
            android.util.Log.e("PrefsManager", "P0-3: 拒绝保存云端凭据——加密存储不可用")
            return false
        }
        sp.edit()
            .putString(KEY_CLOUD_API_KEY, apiKey)
            .apply()
        // URL 与工作区路径非敏感，可走普通偏好
        prefs.edit()
            .putString(KEY_CLOUD_SERVER_URL, cloudUrl)
            .putString(KEY_CLOUD_WORKSPACE_PATH, workspacePath)
            .apply()
        return true
    }

    fun getSavedSessions(): List<SessionItem> {
        val jsonStr = prefs.getString(KEY_SAVED_SESSIONS, null) ?: return emptyList()
        val list = mutableListOf<SessionItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SessionItem(
                        id = obj.optString("id", "default"),
                        title = obj.optString("title", "Main Workspace"),
                        tag = obj.optString("tag", "默认"),
                        isPinned = obj.optBoolean("isPinned", false),
                        isArchived = obj.optBoolean("isArchived", false),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            // fallback to empty on parse error
        }
        return list
    }

    fun saveSessions(sessions: List<SessionItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in sessions) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("tag", item.tag)
                    put("isPinned", item.isPinned)
                    put("isArchived", item.isArchived)
                    put("updatedAt", item.updatedAt)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_SAVED_SESSIONS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun getSavedTags(): List<String> {
        val jsonStr = prefs.getString(KEY_SAVED_TAGS, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    fun saveTags(tags: List<String>) {
        try {
            val jsonArray = JSONArray()
            tags.forEach { jsonArray.put(it) }
            prefs.edit().putString(KEY_SAVED_TAGS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    // v1.6 P0 后台保活：任务状态持久化（App 重启后恢复显示）
    fun saveTaskStatus(status: String, detail: String, sessionId: String) {
        prefs.edit()
            .putString(KEY_TASK_STATUS, status)
            .putString(KEY_TASK_DETAIL, detail)
            .putString(KEY_TASK_SESSION, sessionId)
            .apply()
    }

    fun getTaskStatus(): Triple<String, String, String> {
        return Triple(
            prefs.getString(KEY_TASK_STATUS, "IDLE") ?: "IDLE",
            prefs.getString(KEY_TASK_DETAIL, "") ?: "",
            prefs.getString(KEY_TASK_SESSION, "") ?: ""
        )
    }

    fun clearTaskStatus() {
        prefs.edit()
            .putString(KEY_TASK_STATUS, "IDLE")
            .putString(KEY_TASK_DETAIL, "")
            .putString(KEY_TASK_SESSION, "")
            .apply()
    }

    // v1.6 P1 Model/Agent：选择持久化
    fun saveSelectedAgent(agentId: String) {
        prefs.edit().putString("selected_agent", agentId).apply()
    }

    fun getSelectedAgent(): String {
        return prefs.getString("selected_agent", "") ?: ""
    }

    fun saveSelectedModel(providerId: String, modelId: String) {
        prefs.edit()
            .putString("selected_model_provider", providerId)
            .putString("selected_model_id", modelId)
            .apply()
    }

    fun getSelectedModel(): Pair<String, String> {
        return Pair(
            prefs.getString("selected_model_provider", "") ?: "",
            prefs.getString("selected_model_id", "") ?: ""
        )
    }

    // v1.6 P1 项目管理中心：收藏项目
    fun saveFavoriteProjects(ids: List<String>) {
        prefs.edit().putStringSet("favorite_projects", ids.toSet()).apply()
    }

    fun getFavoriteProjects(): List<String> {
        return prefs.getStringSet("favorite_projects", emptySet())?.toList() ?: emptyList()
    }

    /**
     * v2.3: schema 迁移（幂等）。v0→v1：现有 key 保持不变，仅打版本号戳。
     * 后续版本在此按 version < N 逐级迁移。
     */
    private fun migrateIfNeeded() {
        val current = try {
            prefs.getInt(KEY_SCHEMA_VERSION, 0)
        } catch (e: Exception) {
            0
        }
        if (current >= CURRENT_SCHEMA_VERSION) return
        try {
            var v = current
            // v0 -> v1: 无 key 变更，仅记录版本
            if (v < 1) {
                v = 1
            }
            prefs.edit().putInt(KEY_SCHEMA_VERSION, v).apply()
        } catch (e: Exception) {
            android.util.Log.w("PrefsManager", "schema migrate failed", e)
        }
    }

    fun getSchemaVersion(): Int {
        return try {
            prefs.getInt(KEY_SCHEMA_VERSION, 0)
        } catch (e: Exception) {
            0
        }
    }
}
