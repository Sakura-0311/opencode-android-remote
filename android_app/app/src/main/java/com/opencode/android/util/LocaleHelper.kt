package com.opencode.android.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * v4.3 多语言：运行时 locale 切换（不引入 appcompat，全 API 通用）。
 *
 * 语言显示名一律用各语言的原生名称（如日本語、한국어），这是系统语言选择器的业界惯例，
 * 原生名不随界面语言变化，无需进 strings.xml 翻译。
 */
object LocaleHelper {

    /** locale tag -> 原生显示名；中文默认置顶 */
    val SUPPORTED_LOCALES: List<Pair<String, String>> = listOf(
        "zh-CN" to "中文（简体）",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "ru" to "Русский",
        "pt" to "Português",
        "ar" to "العربية",
    )

    /** 明文偏好文件名（与 PreferencesManager.PLAIN_PREFS_NAME 一致，避免 attachBaseContext 时走完整加解密初始化） */
    private const val PLAIN_PREFS_NAME = "opencode_remote_settings"

    /** attachBaseContext 阶段轻量读取语言设置（不触发 PreferencesManager 的加解密初始化） */
    fun readSavedLocaleTag(context: Context): String = try {
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("app_locale", "") ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 空 tag = 跟随系统；否则按 tag 包一层 configuration context */
    fun wrapContext(base: Context, localeTag: String): Context {
        if (localeTag.isBlank()) return base
        return try {
            val locale = Locale.forLanguageTag(localeTag)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            base.createConfigurationContext(config)
        } catch (_: Exception) {
            base
        }
    }

    /** API 33+：把选择同步到系统「应用语言」设置页 */
    fun syncToSystem(context: Context, localeTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val lm = context.getSystemService(LocaleManager::class.java) ?: return
                lm.applicationLocales = if (localeTag.isBlank()) {
                    LocaleList()
                } else {
                    LocaleList.forLanguageTags(localeTag)
                }
            } catch (_: Exception) {
                // 忽略：不影响应用内生效
            }
        }
    }
}
