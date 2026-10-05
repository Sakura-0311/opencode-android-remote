package com.opencode.android.network

/**
 * relay 消息序号跟踪：幂等去重 + 房间纪元重置。
 *
 * 纯逻辑，可 JVM 单测；持久化经函数注入（生产走 SharedPreferences）。
 * 调用方保证只在同一线程使用（生产是主线程分发阶段）。
 *
 * 语义（与抽取前一致）：
 * - 去重：seq <= lastSeq 的消息丢弃；
 * - 先 track（内存）再 flush（落盘）：处理成功后才持久化，
 *   崩溃时 at-least-once，由去重消化重复；
 * - 纪元：room_epoch 变化说明 relay 房间重建，seq 归零；
 *   旧 relay 不下发 epoch 时保持旧行为。
 */
class RelaySeqTracker(
    private val loadSeq: () -> Long = { 0L },
    private val saveSeq: (Long) -> Unit = {},
    private val loadEpoch: () -> String? = { null },
    private val saveEpoch: (epoch: String, seq: Long) -> Unit = { _, _ -> },
) {
    var lastSeq: Long = 0L
        private set

    var epoch: String? = null
        private set

    /** 从持久化重载（connect 时调用）。 */
    fun reload() {
        lastSeq = loadSeq()
        epoch = loadEpoch()
    }

    /** 是否为重复/过期消息（seq <= lastSeq 则丢弃；seq < 0 表示无序号）。 */
    fun isDuplicate(seq: Long): Boolean = seq >= 0 && seq <= lastSeq

    /** 记录新序号（仅内存）。 */
    fun track(seq: Long) {
        if (seq > lastSeq) lastSeq = seq
    }

    /** 落盘当前序号。 */
    fun flush() = saveSeq(lastSeq)

    /**
     * 检查 room_epoch。返回 true 表示 epoch 发生变化（已重置 seq 并落盘）。
     * 空 epoch（旧 relay）返回 false，保持旧行为。
     */
    fun checkEpoch(newEpoch: String): Boolean {
        if (newEpoch.isBlank()) return false
        val known = epoch
        if (known == null) {
            epoch = newEpoch
            saveEpoch(newEpoch, lastSeq)
            return false
        }
        if (known != newEpoch) {
            epoch = newEpoch
            lastSeq = 0L
            saveEpoch(newEpoch, 0L)
            return true
        }
        return false
    }
}
