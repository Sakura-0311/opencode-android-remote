package com.opencode.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.SessionItem
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = try {
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "opencode_remote_prefs"
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_SECRET = "secret"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_CLOUD_SERVER_URL = "cloud_server_url"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_WORKSPACE_PATH = "cloud_workspace_path"
        private const val KEY_SAVED_SESSIONS = "saved_sessions_json"
        private const val KEY_SAVED_TAGS = "saved_tags_json"

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
        return prefs.getString(KEY_ACCOUNT_ID, "") ?: ""
    }

    fun getSecret(): String {
        return prefs.getString(KEY_SECRET, "") ?: ""
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    }

    fun savePairingInfo(accountId: String, secret: String, relayUrl: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT_ID, accountId)
            .putString(KEY_SECRET, secret)
            .putString(KEY_RELAY_URL, relayUrl)
            .apply()
    }

    fun getCloudServerUrl(): String {
        return prefs.getString(KEY_CLOUD_SERVER_URL, DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
    }

    fun getCloudApiKey(): String {
        return prefs.getString(KEY_CLOUD_API_KEY, "") ?: ""
    }

    fun getCloudWorkspacePath(): String {
        return prefs.getString(KEY_CLOUD_WORKSPACE_PATH, DEFAULT_CLOUD_WORKSPACE) ?: DEFAULT_CLOUD_WORKSPACE
    }

    fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String) {
        prefs.edit()
            .putString(KEY_CLOUD_SERVER_URL, cloudUrl)
            .putString(KEY_CLOUD_API_KEY, apiKey)
            .putString(KEY_CLOUD_WORKSPACE_PATH, workspacePath)
            .apply()
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
}
