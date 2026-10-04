package com.opencode.android.data.local

import android.content.Context
import android.content.SharedPreferences
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ConnectionProfile
import com.opencode.android.data.model.SessionItem
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    // P0-3: 加密存储失败时禁止静默降级（fail-closed）。
    // securePrefs 为 null 表示加密不可用：敏感凭据（Secret / API Key / AccountId）
    // 拒绝读写；非敏感偏好仍可用普通存储。
    //
    // v3.2: 保留旧 EncryptedSharedPreferences 实现至少 1 个版本（迁移源 + 回退）。
    // 新实现为 Tink AEAD（tink-android）。后端选择见 initSecureBackend()。
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

    /** v3.2: 安全存储后端 */
    enum class SecureBackend { LEGACY, TINK }

    private val appContext: Context = context.applicationContext ?: context

    /** v3.2: Tink 后端（初始化失败则为 null，走旧实现） */
    private val tinkStore: SecureKvStore? = try {
        val aead = TinkKeyManager.getOrCreateAead(appContext)
        val backing = appContext.getSharedPreferences(TINK_BACKING_PREFS, Context.MODE_PRIVATE)
        TinkAeadStore(aead, backing)
    } catch (e: Exception) {
        android.util.Log.e("PrefsManager", "v3.2: Tink 初始化失败，回退旧加密存储", e)
        null
    }

    /** v3.2: 本次启动是否发生迁移回退（用户可见提示用） */
    var secureMigrationRolledBack: Boolean = false
        private set

    /** v3.2: 当前生效的安全存储后端 */
    val secureBackend: SecureBackend

    /** v3.2: 诊断页展示的安全存储状态文案 */
    val secureStorageInfo: String

    init {
        val (backend, info, rolledBack) = initSecureBackend()
        secureBackend = backend
        secureStorageInfo = info
        secureMigrationRolledBack = rolledBack
    }

    /**
     * v3.2: 后端选择 + 迁移。
     * - Tink 可用：把旧存储数据迁过去（幂等，可补缺失），成功走 TINK；
     *   迁移失败 → 回退 LEGACY，上报 ACRA，置 rolledBack（旧数据原样保留）。
     * - Tink 不可用：走 LEGACY。
     * - 两者都不可用：secure() 返回 null，调用方 fail-closed。
     */
    private fun initSecureBackend(): Triple<SecureBackend, String, Boolean> {
        val legacy = securePrefs?.let { LegacySecureStore(it) }
        val tink = tinkStore
        if (tink == null) {
            return Triple(
                SecureBackend.LEGACY,
                if (legacy != null) "旧版加密存储（Tink 不可用）" else "不可用",
                false
            )
        }
        if (legacy != null) {
            when (val r = SecureMigration.migrate(legacy, tink)) {
                is SecureMigration.Result.Success -> {
                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_TINK_MIGRATED, true).apply()
                    val n = r.migratedKeys.size
                    val extra = if (n > 0) "（本次迁移 $n 项）" else "（已迁移）"
                    return Triple(SecureBackend.TINK, "Tink ${TinkKeyManager.TINK_VERSION}$extra", false)
                }
                is SecureMigration.Result.Failure -> {
                    android.util.Log.e("PrefsManager", "v3.2: 存储迁移失败 key=${r.failedKey}，回退旧实现", r.cause)
                    try {
                        com.opencode.android.util.CrashReporting.reportNonFatal(
                            appContext,
                            RuntimeException("SecureMigration 失败已回退: ${r.failedKey}", r.cause)
                        )
                    } catch (_: Exception) { }
                    return Triple(SecureBackend.LEGACY, "旧版加密存储（Tink 迁移失败，已回退）", true)
                }
            }
        }
        // 无旧数据：直接走 Tink
        return Triple(SecureBackend.TINK, "Tink ${TinkKeyManager.TINK_VERSION}（新设备）", false)
    }

    /** v3.2: 当前生效的安全存储；null 表示加密不可用（fail-closed） */
    private fun secure(): SecureKvStore? = when (secureBackend) {
        SecureBackend.TINK -> tinkStore
        SecureBackend.LEGACY -> securePrefs?.let { LegacySecureStore(it) }
    }

    /** 加密存储是否可用；为 false 时禁止保存任何敏感凭据 */
    val isSecureStorageAvailable: Boolean get() = secure() != null

    // v4.1: E2EE 密钥材料（敏感，只走 secure()；不可用时返回 null / 抛异常由调用方降级）
    fun getE2eePrivateKey(): String? = secure()?.get("e2ee_privkey")
    fun setE2eePrivateKey(privateKeyB64: String) {
        secure()?.put("e2ee_privkey", privateKeyB64)
            ?: throw IllegalStateException("安全存储不可用，拒绝保存 E2EE 私钥")
    }

    fun getE2eePeerPubkey(deviceId: String): String? =
        secure()?.get("e2ee_peer_$deviceId")

    fun setE2eePeerPubkey(deviceId: String, pubkeyB64: String) {
        secure()?.put("e2ee_peer_$deviceId", pubkeyB64)
            ?: throw IllegalStateException("安全存储不可用，拒绝保存 E2EE 对端公钥")
    }

    fun removeE2eePeerPubkey(deviceId: String) {
        secure()?.remove("e2ee_peer_$deviceId")
    }

    // v4.0: 非敏感偏好统一走明文存储（敏感 key 只走 secure()，绝不进明文）。
    // 旧版本数据由 migratePrefsToPlainIfNeeded() 一次性搬运。
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * v4.0: 旧加密存储文件清理（一次性）。
     * v3.2 迁移后旧 EncryptedSharedPreferences 文件仍保留；本版在 Tink 生效后删除它：
     * - 非敏感 key 先从旧加密文件搬到明文 prefs（敏感 key 跳过，绝不进明文）；
     * - Tink 生效时旧文件已无活数据，删除；LEGACY 回退时保留（仍是 live 安全存储）。
     * 从 v3.1 直接升级的用户：v3.2 的 SecureMigration 先跑（initSecureBackend），
     * 本清理在其之后执行，顺序安全。
     */
    private fun migratePrefsToPlainIfNeeded() {
        if (prefs.getBoolean(KEY_V4_PREFS_CLEANED, false)) return
        val old = securePrefs
        if (old != null) {
            try {
                val sensitive = setOf(KEY_ACCOUNT_ID, KEY_SECRET, KEY_CLOUD_API_KEY)
                val sensitivePrefixes = listOf(KEY_PROFILE_SECRET_PREFIX, KEY_PROFILE_CLOUD_KEY_PREFIX)
                val editor = prefs.edit()
                var copied = 0
                for ((k, v) in old.all) {
                    if (k in sensitive || sensitivePrefixes.any { k.startsWith(it) }) continue
                    if (prefs.contains(k)) continue
                    when (v) {
                        is String -> editor.putString(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                        is Boolean -> editor.putBoolean(k, v)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(k, v as Set<String>)
                        }
                    }
                    copied++
                }
                editor.apply()
                android.util.Log.i("PrefsManager", "v4.0: 非敏感偏好迁移 $copied 项到明文存储")
            } catch (e: Exception) {
                android.util.Log.w("PrefsManager", "v4.0: 偏好迁移失败，保留旧文件", e)
                return
            }
        }
        if (secureBackend == SecureBackend.TINK) {
            try {
                val deleted = appContext.deleteSharedPreferences(PREFS_NAME)
                android.util.Log.i("PrefsManager", "v4.0: 旧加密存储文件删除结果=$deleted")
            } catch (e: Exception) {
                android.util.Log.w("PrefsManager", "v4.0: 删除旧加密存储文件失败", e)
            }
        } else {
            android.util.Log.i("PrefsManager", "v4.0: LEGACY 回退中，保留旧加密存储文件")
        }
        prefs.edit().putBoolean(KEY_V4_PREFS_CLEANED, true).apply()
    }

    // v2.3: 存储 schema 版本。只增不改 key；变更带幂等迁移。
    init {
        migratePrefsToPlainIfNeeded()
        migrateIfNeeded()
    }

    companion object {
        private const val PREFS_NAME = "opencode_remote_prefs"
        // v4.0: 非敏感偏好明文存储（敏感 key 只走 secure()/Tink）
        private const val PLAIN_PREFS_NAME = "opencode_remote_settings"
        private const val KEY_V4_PREFS_CLEANED = "v4_prefs_cleaned"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val KEY_APP_MODE = "app_mode"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val KEY_SECRET = "secret"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_CLOUD_SERVER_URL = "cloud_server_url"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_WORKSPACE_PATH = "cloud_workspace_path"
        // v2.5: 多连接 profiles
        private const val KEY_PROFILES = "connection_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_PROFILE_SECRET_PREFIX = "secret_profile_"
        private const val KEY_PROFILE_CLOUD_KEY_PREFIX = "cloud_api_key_profile_"
        private const val KEY_SAVED_SESSIONS = "saved_sessions_json"
        private const val KEY_SAVED_TAGS = "saved_tags_json"
        // v1.6 P0 后台保活：任务状态持久化
        private const val KEY_TASK_STATUS = "task_status"
        private const val KEY_TASK_DETAIL = "task_status_detail"
        private const val KEY_TASK_SESSION = "task_session_id"

        private const val DEFAULT_RELAY_URL = ""
        private const val DEFAULT_CLOUD_URL = ""
        private const val DEFAULT_CLOUD_WORKSPACE = "/workspace"
        // v3.2: Tink 存储
        private const val TINK_BACKING_PREFS = "opencode_tink_values"
        private const val KEY_TINK_MIGRATED = "secure_tink_migrated"
        private const val KEY_MIGRATION_NOTICE_DISMISSED = "secure_migration_notice_dismissed"
        private const val KEY_OLD_RELAY_WARN_PREFIX = "old_relay_warn_dismissed_"
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
        return secure()?.get(KEY_ACCOUNT_ID) ?: ""
    }

    fun getSecret(): String {
        return secure()?.get(KEY_SECRET) ?: ""
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    }

    /**
     * P0-3: 保存配对敏感凭据。加密存储不可用时返回 false，调用方必须提示用户且不得继续。
     */
    fun savePairingInfo(accountId: String, secret: String, relayUrl: String): Boolean {
        val sp = secure() ?: run {
            android.util.Log.e("PrefsManager", "P0-3: 拒绝保存配对凭据——加密存储不可用")
            return false
        }
        sp.put(KEY_ACCOUNT_ID, accountId)
        sp.put(KEY_SECRET, secret)
        // relayUrl 非敏感，可走普通偏好
        prefs.edit().putString(KEY_RELAY_URL, relayUrl).apply()
        // v2.5: 同步到 active profile（密钥进 profile 槽，旧 key 保留作保底）
        try {
            val pid = getActiveProfileId()
            if (pid.isNotEmpty()) {
                sp.put(KEY_PROFILE_SECRET_PREFIX + pid, secret)
                getActiveProfile()?.let { updateProfile(it.copy(relayUrl = relayUrl, accountId = accountId)) }
            }
        } catch (_: Exception) { }
        return true
    }

    fun getCloudServerUrl(): String {
        return prefs.getString(KEY_CLOUD_SERVER_URL, DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
    }

    fun getCloudApiKey(): String {
        return secure()?.get(KEY_CLOUD_API_KEY) ?: ""
    }

    fun getCloudWorkspacePath(): String {
        return prefs.getString(KEY_CLOUD_WORKSPACE_PATH, DEFAULT_CLOUD_WORKSPACE) ?: DEFAULT_CLOUD_WORKSPACE
    }

    /**
     * P0-3: 保存云端敏感凭据。加密存储不可用时返回 false，调用方必须提示用户且不得继续。
     */
    fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String): Boolean {
        val sp = secure() ?: run {
            android.util.Log.e("PrefsManager", "P0-3: 拒绝保存云端凭据——加密存储不可用")
            return false
        }
        sp.put(KEY_CLOUD_API_KEY, apiKey)
        // URL 与工作区路径非敏感，可走普通偏好
        prefs.edit()
            .putString(KEY_CLOUD_SERVER_URL, cloudUrl)
            .putString(KEY_CLOUD_WORKSPACE_PATH, workspacePath)
            .apply()
        // v2.5: 同步到 active profile
        try {
            val pid = getActiveProfileId()
            if (pid.isNotEmpty()) {
                sp.put(KEY_PROFILE_CLOUD_KEY_PREFIX + pid, apiKey)
                getActiveProfile()?.let { updateProfile(it.copy(cloudUrl = cloudUrl, cloudWorkspace = workspacePath)) }
            }
        } catch (_: Exception) { }
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

    // ============ v2.5: 多连接 profiles ============

    /**
     * 获取全部 profiles。首次调用时把旧单配置迁移为第一个 profile（名为"默认连接"），
     * 并把旧密钥复制到该 profile 的密钥槽；旧 key 保留一个版本作为读取保底。
     * 迁移失败时返回空列表，调用方应继续使用旧单配置读写（失败保底）。
     */
    fun getProfiles(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val list = ConnectionProfile.listFromJson(raw)
            if (list.isNotEmpty()) return list
        }
        return try {
            val legacy = ConnectionProfile(
                id = "legacy_default",
                name = "默认连接",
                mode = getAppMode(),
                relayUrl = getRelayUrl(),
                accountId = getAccountId(),
                cloudUrl = getCloudServerUrl(),
                cloudWorkspace = getCloudWorkspacePath()
            )
            secure()?.let { sp ->
                val oldSecret = getSecret()
                val oldCloudKey = getCloudApiKey()
                if (oldSecret.isNotEmpty()) sp.put(KEY_PROFILE_SECRET_PREFIX + legacy.id, oldSecret)
                if (oldCloudKey.isNotEmpty()) sp.put(KEY_PROFILE_CLOUD_KEY_PREFIX + legacy.id, oldCloudKey)
            }
            saveProfiles(listOf(legacy))
            setActiveProfileId(legacy.id)
            listOf(legacy)
        } catch (e: Exception) {
            android.util.Log.e("PrefsManager", "v2.5: profile 迁移失败，保留旧单配置", e)
            emptyList()
        }
    }

    fun saveProfiles(list: List<ConnectionProfile>) {
        prefs.edit().putString(KEY_PROFILES, ConnectionProfile.listToJson(list)).apply()
    }

    fun getActiveProfileId(): String {
        val profiles = getProfiles()
        val saved = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return if (profiles.any { it.id == saved }) saved!! else profiles.firstOrNull()?.id ?: ""
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()
    }

    fun getActiveProfile(): ConnectionProfile? {
        val id = getActiveProfileId()
        return getProfiles().firstOrNull { it.id == id }
    }

    fun updateProfile(profile: ConnectionProfile) {
        saveProfiles(getProfiles().map { if (it.id == profile.id) profile else it })
    }

    /** 至少保留一个 profile */
    fun deleteProfile(id: String): Boolean {
        val list = getProfiles()
        if (list.size <= 1) return false
        saveProfiles(list.filter { it.id != id })
        secure()?.let {
            it.remove(KEY_PROFILE_SECRET_PREFIX + id)
            it.remove(KEY_PROFILE_CLOUD_KEY_PREFIX + id)
        }
        if (getActiveProfileId() == id) setActiveProfileId(getProfiles().firstOrNull()?.id ?: "")
        return true
    }

    /** profile 密钥：优先读 profile 槽，空则回退旧单配置 key（迁移保底） */
    fun getProfileSecret(profileId: String): String {
        val sp = secure() ?: return ""
        return sp.get(KEY_PROFILE_SECRET_PREFIX + profileId) ?: getSecret()
    }

    fun saveProfileSecret(profileId: String, s: String): Boolean {
        val sp = secure() ?: return false
        sp.put(KEY_PROFILE_SECRET_PREFIX + profileId, s)
        return true
    }

    fun getProfileCloudApiKey(profileId: String): String {
        val sp = secure() ?: return ""
        return sp.get(KEY_PROFILE_CLOUD_KEY_PREFIX + profileId) ?: getCloudApiKey()
    }

    fun saveProfileCloudApiKey(profileId: String, k: String): Boolean {
        val sp = secure() ?: return false
        sp.put(KEY_PROFILE_CLOUD_KEY_PREFIX + profileId, k)
        return true
    }

    // ============ v3.1: 多 desktop 目标选择（按 profile 隔离） ============

    private val targetDesktopStore = TargetDesktopStore(object : TargetDesktopStore.Kv {
        override fun getString(key: String, def: String): String =
            prefs.getString(key, def) ?: def
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    })

    /** 目标电脑 deviceId；空字符串表示未选择，走主 desktop */
    fun saveTargetDesktopId(deviceId: String) {
        targetDesktopStore.saveTargetDesktopId(getActiveProfileId(), deviceId)
    }

    fun getTargetDesktopId(): String {
        return targetDesktopStore.getTargetDesktopId(getActiveProfileId())
    }

    /** 会话与电脑绑定 */
    fun saveSessionDesktopBinding(sessionId: String, deviceId: String) {
        targetDesktopStore.saveSessionBinding(sessionId, deviceId)
    }

    fun getSessionDesktopBinding(sessionId: String): String {
        return targetDesktopStore.getSessionBinding(sessionId)
    }

    /**
     * v3.2: 安全存储迁移回退的一次性用户提示（只弹一次）。
     */
    fun wasSecureMigrationNoticeDismissed(): Boolean =
        prefs.getString(KEY_MIGRATION_NOTICE_DISMISSED, null) != null

    fun dismissSecureMigrationNotice() {
        prefs.edit().putString(KEY_MIGRATION_NOTICE_DISMISSED, "1").apply()
    }

    /** v4.0: v3 旧服务端升级提示（每个 relayUrl 只提示一次） */
    fun wasOldRelayWarnDismissed(relayUrl: String): Boolean =
        prefs.getString(KEY_OLD_RELAY_WARN_PREFIX + relayUrl.hashCode(), null) != null

    fun dismissOldRelayWarn(relayUrl: String) {
        prefs.edit().putString(KEY_OLD_RELAY_WARN_PREFIX + relayUrl.hashCode(), "1").apply()
    }
}
