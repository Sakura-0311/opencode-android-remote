package com.opencode.android.util

import android.os.Handler
import android.content.Context
import android.os.Looper
import com.opencode.android.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * B-12: 应用内更新检查 — 比对 GitHub Releases 最新版
 */
object UpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/Sakura-0311/opencode-android-remote/releases/latest"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String?,
        val releaseNotes: String?,
        val error: String? = null
    )

    /**
     * 在后台线程检查，结果回 main 线程
     */
    fun checkForUpdate(context: Context, callback: (UpdateInfo) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val info = try {
                val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode != 200) {
                    UpdateInfo(false, "", BuildConfig.VERSION_NAME, null, null,
                        context.getString(R.string.update_001, conn.responseCode))
                } else {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "").trim().removePrefix("v")
                    val notes = json.optString("body", "")
                    var dlUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name", "")
                            if (name.endsWith(".apk")) { dlUrl = a.optString("browser_download_url"); break }
                        }
                    }
                    val current = BuildConfig.VERSION_NAME
                    UpdateInfo(
                        hasUpdate = isNewer(tag, current),
                        latestVersion = tag.ifEmpty { context.getString(R.string.update_002) },
                        currentVersion = current,
                        downloadUrl = dlUrl,
                        releaseNotes = notes.takeIf { it.isNotBlank() }
                    )
                }
            } catch (e: Exception) {
                UpdateInfo(false, "", BuildConfig.VERSION_NAME, null, null, context.getString(R.string.update_003, e.message))
            }
            mainHandler.post { callback(info) }
        }.start()
    }

    /** 简单语义版本比较：latest > current 即有更新 */
    private fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(l.size, c.size)
        for (i in 0 until n) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
