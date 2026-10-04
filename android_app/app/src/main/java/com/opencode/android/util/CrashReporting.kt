package com.opencode.android.util

import android.app.Application
import android.content.Context
import android.util.Log
import com.opencode.android.BuildConfig
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.HttpSenderConfiguration
import org.acra.data.StringFormat
import org.acra.sender.HttpSender

/**
 * 对外分发：ACRA 崩溃上报（纯开源，自建 Relay 接收端）。
 *
 * - 默认关闭，用户在菜单中手动开启后重启应用生效（隐私说明承诺）。
 * - 上报地址 = 用户自己配置的 Relay 服务器 + /api/crash-report，不经过任何第三方。
 * - 仅上报脱敏字段：版本、机型、Android 版本、堆栈、时间；不含 logcat、设备 ID、
 *   SharedPreferences 内容、聊天记录、密钥。
 *
 * ACRA 5.11+ 使用 @AutoDsl 生成的 CoreConfigurationBuilder（属性直接赋值），
 * HttpSenderConfiguration 为普通 data class，直接构造后放入 pluginConfigurations。
 */
object CrashReporting {

    const val PREFS_NAME = "opencode_remote_prefs"
    const val KEY_OPT_IN = "crash_report_opt_in"
    private const val KEY_RELAY_URL = "relay_url"
    private const val TAG = "CrashReporting"

    fun isOptedIn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPT_IN, false)
    }

    fun setOptedIn(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OPT_IN, enabled).apply()
    }

    /** 上报接收端地址（用户自建 Relay），未配置则返回 null */
    fun reportEndpoint(context: Context): String? {
        val relayUrl = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RELAY_URL, "").orEmpty().trim()
        if (relayUrl.isEmpty()) return null
        val httpBase = when {
            relayUrl.startsWith("wss://") -> "https://" + relayUrl.removePrefix("wss://")
            relayUrl.startsWith("ws://") -> "http://" + relayUrl.removePrefix("ws://")
            relayUrl.startsWith("https://") || relayUrl.startsWith("http://") -> relayUrl
            else -> return null
        }
        return httpBase.trimEnd('/') + "/api/crash-report"
    }

    /**
     * 在 Application.onCreate 中调用。未开启或无接收端时直接返回，不初始化 ACRA。
     */
    fun init(app: Application) {
        if (!isOptedIn(app)) {
            Log.i(TAG, "crash reporting disabled by user")
            return
        }
        val uri = reportEndpoint(app)
        if (uri.isNullOrEmpty()) {
            Log.w(TAG, "crash reporting opted in but no relay url configured")
            return
        }
        try {
            val builder = CoreConfigurationBuilder()
            builder.buildConfigClass = BuildConfig::class.java
            builder.reportFormat = StringFormat.JSON
            builder.deleteUnapprovedReportsOnApplicationStart = true
            // 最小脱敏字段集：无 logcat、无设备 ID、无偏好内容
            builder.reportContent = listOf(
                ReportField.REPORT_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.PHONE_MODEL,
                ReportField.BRAND,
                ReportField.STACK_TRACE,
                ReportField.USER_APP_START_DATE,
                ReportField.USER_CRASH_DATE
            )
            builder.pluginConfigurations = listOf(
                HttpSenderConfiguration(
                    uri = uri,
                    httpMethod = HttpSender.Method.POST,
                    enabled = true
                )
            )
            ACRA.init(app, builder)
            Log.i(TAG, "crash reporting enabled -> $uri")
        } catch (e: Exception) {
            Log.w(TAG, "ACRA init failed", e)
        }
    }

    /**
     * v3.2: 非致命异常上报兜底（如安全存储迁移失败回退）。
     * 仅在用户已 opt-in 且 ACRA 已初始化时上报；否则只记 log。
     */
    fun reportNonFatal(context: Context, e: Throwable) {
        try {
            if (!isOptedIn(context)) {
                Log.i(TAG, "reportNonFatal skipped (not opted in): ${e.message}")
                return
            }
            org.acra.ACRA.getErrorReporter().handleSilentException(e)
            Log.i(TAG, "reportNonFatal sent: ${e.message}")
        } catch (re: Exception) {
            Log.w(TAG, "reportNonFatal failed", re)
        }
    }
}
