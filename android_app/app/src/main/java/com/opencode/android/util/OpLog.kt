package com.opencode.android.util

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.5: 本地操作记录（审计用）。
 * - 只记：工具审批/拒绝、中断任务、撤销设备、配对成功、配置导入/导出
 * - 每条含时间戳 + 操作路径/对象名；绝不记录 prompt 内容与代码
 * - 上限 200 条，超限从最旧删除（FIFO）
 */
object OpLog {

    enum class OpType(val label: String) {
        APPROVE("审批通过"),
        REJECT("审批拒绝"),
        ABORT("中断任务"),
        REVOKE("撤销设备"),
        PAIR("配对成功"),
        CONFIG_EXPORT("导出配置"),
        CONFIG_IMPORT("导入配置")
    }

    data class Entry(val ts: Long, val type: OpType, val detail: String) {
        fun timeLabel(): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    private const val FILE_NAME = "op_log.jsonl"
    private const val MAX_ENTRIES = 200

    private fun fileOf(context: Context): File = File(context.filesDir, FILE_NAME)

    /** detail 只放路径/对象名，不放 prompt 与代码 */
    @Synchronized
    fun record(context: Context, type: OpType, detail: String) {
        try {
            val safe = detail.take(120).replace("\n", " ")
            val line = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("type", type.name)
                .put("detail", safe)
                .toString()
            val f = fileOf(context)
            val existing = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            existing.add(line)
            while (existing.size > MAX_ENTRIES) existing.removeAt(0)
            f.writeText(existing.joinToString("\n"))
        } catch (_: Exception) { }
    }

    @Synchronized
    fun read(context: Context): List<Entry> {
        return try {
            val f = fileOf(context)
            if (!f.exists()) return emptyList()
            f.readLines().mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    Entry(
                        ts = o.getLong("ts"),
                        type = OpType.valueOf(o.getString("type")),
                        detail = o.optString("detail")
                    )
                } catch (_: Exception) { null }
            }.sortedByDescending { it.ts }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try { fileOf(context).delete() } catch (_: Exception) { }
    }
}
