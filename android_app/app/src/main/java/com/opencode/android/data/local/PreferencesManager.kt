package com.opencode.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.opencode.android.data.model.AppMode

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "opencode_remote_prefs"
        private const val KEY_APP_MODE = "pref_app_mode"
        
        // 电脑中继模式键
        private const val KEY_ACCOUNT_ID = "pref_account_id"
        private const val KEY_SECRET = "pref_secret"
        private const val KEY_RELAY_URL = "pref_relay_url"
        private const val DEFAULT_ACCOUNT = "user_dev_001"
        private const val DEFAULT_RELAY = "ws://10.0.2.2:8765"

        // 云端模式键
        private const val KEY_CLOUD_URL = "pref_cloud_url"
        private const val KEY_CLOUD_KEY = "pref_cloud_key"
        private const val KEY_CLOUD_WORKSPACE = "pref_cloud_workspace"
        private const val DEFAULT_CLOUD_URL = "https://opencode.yourdomain.com:4096"
        private const val DEFAULT_CLOUD_WORKSPACE = "/workspace"
    }

    fun getAppMode(): AppMode {
        val modeStr = prefs.getString(KEY_APP_MODE, AppMode.DESKTOP_RELAY.name)
        return try {
            AppMode.valueOf(modeStr ?: AppMode.DESKTOP_RELAY.name)
        } catch (e: Exception) {
            AppMode.DESKTOP_RELAY
        }
    }

    fun saveAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.name).apply()
    }

    fun getAccountId(): String {
        return prefs.getString(KEY_ACCOUNT_ID, DEFAULT_ACCOUNT) ?: DEFAULT_ACCOUNT
    }

    fun getSecret(): String {
        return prefs.getString(KEY_SECRET, "") ?: ""
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY) ?: DEFAULT_RELAY
    }

    fun savePairingInfo(accountId: String, secret: String, relayUrl: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT_ID, accountId.trim())
            .putString(KEY_SECRET, secret.trim())
            .putString(KEY_RELAY_URL, relayUrl.trim())
            .apply()
    }

    // 云端配置存取
    fun getCloudServerUrl(): String {
        return prefs.getString(KEY_CLOUD_URL, DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
    }

    fun getCloudApiKey(): String {
        return prefs.getString(KEY_CLOUD_KEY, "") ?: ""
    }

    fun getCloudWorkspacePath(): String {
        return prefs.getString(KEY_CLOUD_WORKSPACE, DEFAULT_CLOUD_WORKSPACE) ?: DEFAULT_CLOUD_WORKSPACE
    }

    fun saveCloudConfig(url: String, apiKey: String, workspace: String) {
        prefs.edit()
            .putString(KEY_CLOUD_URL, url.trim())
            .putString(KEY_CLOUD_KEY, apiKey.trim())
            .putString(KEY_CLOUD_WORKSPACE, workspace.trim())
            .apply()
    }
}
