package com.opencode.android.util

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.3: 环形文件日志（2×1MB），统一脱敏。
 *
 * - 只记事件与错误码，不记 prompt / 代码正文 / 原始消息体。
 * - [redact] 会掩盖 Secret、password、Authorization、token、URL query 等。
 * - [exportLogFile] 把当前日志拷到 cache 目录供用户导出分享。
 *
 * 纯 java.io 实现（不依赖 android.util.Log），redact 可单测。
 */
object AppLog {

    const val MAX_FILES = 2
    const val MAX_BYTES = 1024L * 1024L

    @Volatile private var logDir: File? = null
    private val lock = Any()
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ---- 脱敏规则 ----
    // "Bearer <token>" 两段式先处理（kv 规则只会盖住 Bearer 这个词本身）
    private val bearerPattern = Regex("""(?i)bearer\s+[A-Za-z0-9\\-._~+/=]+""")
    // key= value / key: value / key "value" 形式，key 命中敏感词则掩盖值
    private val kvPattern =
        Regex("""(?i)(secret|password|passwd|api[_-]?key|authorization|token|private[_-]?key)\s*["':=]+\s*["']?([^"'{},\s]+)""")
    // URL query 一律掩盖（可能含 token）
    private val urlQueryPattern = Regex("""(https?://[^\s"'?]+)\?[^\s"']*""")
    // JSON 字符串里的敏感键
    private val sensitiveJsonKeys = setOf(
        "secret", "password", "passwd", "api_key", "apikey", "authorization",
        "token", "private_key", "client_msg_id"
    )

    fun init(dir: File) {
        synchronized(lock) {
            logDir = dir
            try {
                if (!dir.exists()) dir.mkdirs()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** 文本脱敏：掩盖敏感 kv 与 URL query */
    fun redact(text: String): String {
        var s = bearerPattern.replace(text, "Bearer ***")
        s = kvPattern.replace(s) { m -> "${m.groupValues[1]}=***" }
        s = urlQueryPattern.replace(s) { m -> "${m.groupValues[1]}?***" }
        return s
    }

    /** JSONObject 脱敏：敏感键掩盖；prompt / 代码正文类键直接丢弃 */
    fun redactJson(obj: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val kl = k.lowercase()
            when {
                kl in sensitiveJsonKeys -> out.put(k, "***")
                // 正文类字段不进日志
                kl in setOf("prompt", "content", "chunk", "raw_content", "diff_lines", "stack_trace") ->
                    out.put(k, "<omitted ${obj.optString(k).length} chars>")
                else -> {
                    val v = obj.opt(k)
                    out.put(k, if (v is JSONObject) redactJson(v) else v)
                }
            }
        }
        return out
    }

    private fun currentFile(): File? {
        val dir = logDir ?: return null
        val f0 = File(dir, "applog-0.txt")
        val f1 = File(dir, "applog-1.txt")
        return try {
            // 写较小的文件，满了就轮换
            if (!f0.exists() || f0.length() < MAX_BYTES) f0
            else if (!f1.exists() || f1.length() < MAX_BYTES) f1
            else {
                // 两个都满：删最旧的（按修改时间），从头写
                val oldest = if (f0.lastModified() <= f1.lastModified()) f0 else f1
                oldest.delete()
                oldest
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun write(level: String, tag: String, msg: String) {
        val f = currentFile() ?: return
        val line = "${dateFmt.format(Date())} $level/$tag: ${redact(msg)}\n"
        synchronized(lock) {
            try {
                // 轮换检查：单次写入前若超限则换文件
                val target = currentFile() ?: return
                target.appendText(line)
            } catch (e: Exception) {
                // 日志失败不影响业务
            }
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)

    /**
     * 导出日志：将当前两个环形文件合并拷到目标文件（已脱敏写入，无需二次处理）。
     * 返回目标文件，失败返回 null。
     */
    fun exportLogFile(dest: File): File? {
        val dir = logDir ?: return null
        return try {
            val sb = StringBuilder()
            sb.append("=== OpenCode Remote 日志导出 ${dateFmt.format(Date())} ===\n")
            sb.append("(已脱敏：不含 Secret / 口令 / prompt 与代码正文)\n\n")
            listOf(File(dir, "applog-0.txt"), File(dir, "applog-1.txt"))
                .filter { it.exists() }
                .sortedBy { it.lastModified() }
                .forEach { sb.append(it.readText()).append("\n") }
            dest.writeText(sb.toString())
            dest
        } catch (e: Exception) {
            null
        }
    }
}
