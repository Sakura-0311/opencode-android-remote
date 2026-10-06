package com.opencode.android.data.local

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v5.0.3 (B-6): 按会话缓存最近 N 条聊天记录。
 *
 * 此前消息只活在 ViewModel 内存里：App 主打后台保活，任务在后台继续跑，
 * 但用户划掉最近任务或进程被回收后回来什么都看不到；切换会话也会清空。
 * 这里做纯本地缓存（不碰协议、不依赖 Android 框架），写文件在 IO 线程。
 *
 * 落盘时机：流结束、切换会话、App 退到后台、ViewModel 销毁——
 * **不在每个 chunk 后写**，否则流式输出期间会高频写盘。
 */
class MessageStore(
    private val baseDir: File,
    private val maxPerSession: Int = 200
) {

    fun load(sessionId: String): List<ChatMessage> {
        if (sessionId.isBlank()) return emptyList()
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = runCatching { MessageRole.valueOf(o.optString("role")) }
                    .getOrDefault(MessageRole.ASSISTANT)
                out.add(
                    ChatMessage(
                        id = o.optString("id"),
                        role = role,
                        content = o.optString("content"),
                        timestamp = o.optLong("timestamp"),
                        isStreaming = false,
                        toolEvents = emptyList(),
                        isError = o.optBoolean("isError", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            // 缓存损坏不该影响使用：删掉坏文件，按空会话继续
            runCatching { file.delete() }
            emptyList()
        }
    }

    fun save(sessionId: String, messages: List<ChatMessage>) {
        if (sessionId.isBlank() || messages.isEmpty()) return
        val snapshot = messages.takeLast(maxPerSession)
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val arr = JSONArray()
            for (m in snapshot) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("role", m.role.name)
                    put("content", m.content)
                    put("timestamp", m.timestamp)
                    put("isError", m.isError)
                })
            }
            // 先写临时文件再改名：避免进程被杀时留下半截 JSON
            val target = fileFor(sessionId)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(arr.toString())
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            // 缓存写失败静默降级：聊天本身仍在内存里可用
        }
    }

    fun clear(sessionId: String) {
        runCatching { fileFor(sessionId).delete() }
    }

    /** 会话 id 可能含路径分隔符等，做一次清洗；同名即覆盖，不做唯一性保证 */
    private fun fileFor(sessionId: String): File {
        val safe = sessionId.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
        }.joinToString("").take(64)
        val name = if (safe.isBlank()) "default" else safe
        return File(baseDir, "msg_$name.json")
    }
}