package com.opencode.android.data.local

import com.opencode.android.R
import android.content.Context
import android.content.SharedPreferences
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ConnectionProfile
import com.opencode.android.data.model.SessionItem
import org.json.JSONArray
import org.json.JSONObject
import androidx.annotation.VisibleForTesting
import com.opencode.android.coordinator.DeviceRoutingPrefs
import com.opencode.android.coordinator.PairingPrefs
import com.opencode.android.coordinator.TaskStatusPrefs

class PreferencesManager private constructor(context: Context) : com.opencode.android.security.E2eePrefs,
    PairingPrefs, TaskStatusPrefs, DeviceRoutingPrefs {

    // P0-3: 加密存储失败时禁止静默降级（fail-closed）。
    // secure() 为 null 表示加密不可用：敏感凭据（Secret / API Key / AccountId）
    // 拒绝读写；非敏感偏好仍可用普通存储。
    //
    // v5.0.2: 删除旧的 EncryptedSharedPreferences(security-crypto) 后端与整条迁移链。
    // 项目尚无线上用户，不存在需要迁移的存量数据，因此：
    //   - 不再依赖 androidx.security:security-crypto（官方已弃用、1.1.0-alpha06 多年未更新）；
    //   - 删除 SecureMigration、LEGACY 回退后端、legacy 使用计数与旧文件清理；
    //   - 安全存储只剩一种实现：Tink AEAD + Android Keystore 主密钥。
    // Tink 不可用时 secure() 返回 null，调用方 fail-closed 拒绝写入，绝不落明文。

    /** 安全存储后端（v5.0.2 起只有 Tink 一种；枚举保留以兼容既有调用方与诊断文案） */
    enum class SecureBackend { TINK }

    private val appContext: Context = context.applicationContext ?: context

    /** Tink 后端；初始化失败为 null → 敏感凭据一律拒绝读写（fail-closed） */
    private val tinkStore: SecureKvStore? = try {
        val aead = TinkKeyManager.getOrCreateAead(appContext)
        val backing = appContext.getSharedPreferences(TINK_BACKING_PREFS, Context.MODE_PRIVATE)
        TinkAeadStore(aead, backing)
    } catch (e: Exception) {
        android.util.Log.e("PrefsManager", "Tink 初始化失败，敏感凭据将被拒绝保存（fail-closed）", e)
        try {
            com.opencode.android.util.CrashReporting.reportNonFatal(appContext, e)
        } catch (_: Exception) { }
        null
    }

    /** 当前生效的安全存储后端 */
    val secureBackend: SecureBackend = SecureBackend.TINK

    /** 诊断页展示的安全存储状态文案 */
    val secureStorageInfo: String = if (tinkStore != null) {
        appContext.getString(R.string.prefs_006, TinkKeyManager.TINK_VERSION)
    } else {
        appContext.getString(R.string.prefs_002)
    }

    /** 当前生效的安全存储；null 表示加密不可用（fail-closed） */
    private fun secure(): SecureKvStore? = tinkStore

    /** 加密存储是否可用；为 false 时禁止保存任何敏感凭据 */
    override val isSecureStorageAvailable: Boolean get() = secure() != null

    // v4.2: E2EE 运行时开关（非敏感，明文存储；默认关闭）
    /**
     * v4.3 M-2: 存储的 secret 是否为房间主 secret。
     * 手动配对（用户直接填主 secret）= true；扫码配对（device_secret）= false。
     * 仅 true 时可做 E2EE 公钥 HMAC 绑定校验。
     */
    override var secretIsMaster: Boolean
        get() = prefs.getBoolean("secret_is_master", false)
        set(v) { prefs.edit().putBoolean("secret_is_master", v).apply() }

    override var isE2eeEnabled: Boolean
        get() = prefs.getBoolean("e2ee_enabled", false)
        set(v) { prefs.edit().putBoolean("e2ee_enabled", v).apply() }

    // v4.3: 界面语言 locale tag（非敏感，明文存储；空字符串=跟随系统）
    var appLocale: String
        get() = prefs.getString("app_locale", "") ?: ""
        set(v) { prefs.edit().putString("app_locale", v).apply() }

    // v4.1: E2EE 密钥材料（敏感，只走 secure()；不可用时返回 null / 抛异常由调用方降级）
    override fun getE2eePrivateKey(): String? = secure()?.get("e2ee_privkey")
    override fun setE2eePrivateKey(privateKeyB64: String) {
        secure()?.put("e2ee_privkey", privateKeyB64)
            ?: throw IllegalStateException("安全存储不可用，拒绝保存 E2EE 私钥")
    }

    override fun getE2eePeerPubkey(deviceId: String): String? =
        secure()?.get("e2ee_peer_$deviceId")

    override fun setE2eePeerPubkey(deviceId: String, pubkeyB64: String) {
        secure()?.put("e2ee_peer_$deviceId", pubkeyB64)
            ?: throw IllegalStateException("安全存储不可用，拒绝保存 E2EE 对端公钥")
    }

    override fun removeE2eePeerPubkey(deviceId: String) {
        secure()?.remove("e2ee_peer_$deviceId")
    }

    // v4.6.0: 本机 relay device_id（非敏感，明文存储）
    override fun getE2eeOwnRelayDeviceId(): String? =
        prefs.getString("e2ee_own_relay_device_id", null)
    override fun setE2eeOwnRelayDeviceId(id: String) {
        prefs.edit().putString("e2ee_own_relay_device_id", id).apply()
    }

    // v4.6.0: E2EE 序号计数器（非敏感，明文存储；key 做清洗防注入）
    private fun seqKey(peerId: String, direction: String): String {
        val safe = peerId.filter { it.isLetterOrDigit() || it in "-_." }.take(64)
        val dir = if (direction == "d2m") "d2m" else "m2d"
        return "e2ee_seq_${safe}_$dir"
    }
    override fun getE2eeSeq(peerId: String, direction: String): Long =
        prefs.getLong(seqKey(peerId, direction), 0L)
    override fun setE2eeSeq(peerId: String, direction: String, seq: Long) {
        prefs.edit().putLong(seqKey(peerId, direction), seq).apply()
    }

    // v4.0: 非敏感偏好统一走明文存储（敏感 key 只走 secure()，绝不进明文）。
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var INSTANCE: PreferencesManager? = null

        /**
         * v4.3.2 A1: 单例。之前 Application 和 ViewModel 各 new 一个实例，
         * 两个 EncryptedSharedPreferences 并发初始化会导致 keyset 竞争，
         * 升级后迁移读到写坏的旧文件（SecurityException 回退 LEGACY）。
         */
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext ?: context)
                    .also { INSTANCE = it }
            }
        }

        /** 仅供测试：重置单例。 */
        @VisibleForTesting
        fun resetForTest() {
            synchronized(this) { INSTANCE = null }
        }

        private const val PREFS_NAME = "opencode_remote_prefs"
        // v4.0: 非敏感偏好明文存储（敏感 key 只走 secure()/Tink）
        private const val PLAIN_PREFS_NAME = "opencode_remote_settings"
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
        // Tink 存储
        private const val TINK_BACKING_PREFS = "opencode_tink_values"
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

    override fun getSecret(): String {
        return secure()?.get(KEY_SECRET) ?: ""
    }

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    }

    /**
     * P0-3: 保存配对敏感凭据。加密存储不可用时返回 false，调用方必须提示用户且不得继续。
     */
    override fun savePairingInfo(accountId: String, secret: String, relayUrl: String): Boolean {
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
    override fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String): Boolean {
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
                        // v4.3 M-4: 存量中文标签一次性迁移为稳定 key
                        tag = com.opencode.android.util.migrateTag(obj.optString("tag", null)),
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
    override fun saveTaskStatus(status: String, detail: String, sessionId: String) {
        prefs.edit()
            .putString(KEY_TASK_STATUS, status)
            .putString(KEY_TASK_DETAIL, detail)
            .putString(KEY_TASK_SESSION, sessionId)
            .apply()
    }

    override fun getTaskStatus(): Triple<String, String, String> {
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
    override fun saveFavoriteProjects(ids: List<String>) {
        prefs.edit().putStringSet("favorite_projects", ids.toSet()).apply()
    }

    override fun getFavoriteProjects(): List<String> {
        return prefs.getStringSet("favorite_projects", emptySet())?.toList() ?: emptyList()
    }

    // ============ v2.5: 多连接 profiles ============

    /**
     * 获取全部 profiles。
     *
     * v5.0.2: 删除「旧单配置 → 首个 profile」的迁移分支。项目尚无线上用户，
     * 新装即为空列表，由配对页创建第一个 profile；旧 key（KEY_SECRET 等）
     * 仍作为「当前凭据」被配对页读写，但不再自动生成 legacy_default。
     */
    fun getProfiles(): List<ConnectionProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            return ConnectionProfile.listFromJson(raw)
        }
        return emptyList()
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

    /**
     * profile 密钥：只读该 profile 自己的槽。
     *
     * v5.0.2: 删除对全局 `KEY_SECRET` 的回退。项目尚无线上用户，不存在需要保底的
     * 存量数据；而保留回退正是「切到一个从未配对过的新 profile 时，把上一个
     * （全局）房间 secret 发往新 profile 的 relayUrl」这一串号风险的来源。
     */
    fun getProfileSecret(profileId: String): String {
        if (profileId.isEmpty()) return getSecret()
        return secure()?.get(KEY_PROFILE_SECRET_PREFIX + profileId) ?: ""
    }

    fun saveProfileSecret(profileId: String, s: String): Boolean {
        val sp = secure() ?: return false
        sp.put(KEY_PROFILE_SECRET_PREFIX + profileId, s)
        return true
    }

    /** profile 云端 Key：只读自己的槽，理由同 [getProfileSecret]。 */
    fun getProfileCloudApiKey(profileId: String): String {
        if (profileId.isEmpty()) return getCloudApiKey()
        return secure()?.get(KEY_PROFILE_CLOUD_KEY_PREFIX + profileId) ?: ""
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
    override fun saveTargetDesktopId(deviceId: String) {
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
     * v5.0.2: 迁移链已删除（项目无线上用户），这两个方法随之移除。
     */

    /** v4.0: v3 旧服务端升级提示（每个 relayUrl 只提示一次） */
    fun wasOldRelayWarnDismissed(relayUrl: String): Boolean =
        prefs.getString(KEY_OLD_RELAY_WARN_PREFIX + relayUrl.hashCode(), null) != null

    fun dismissOldRelayWarn(relayUrl: String) {
        prefs.edit().putString(KEY_OLD_RELAY_WARN_PREFIX + relayUrl.hashCode(), "1").apply()
    }
}
