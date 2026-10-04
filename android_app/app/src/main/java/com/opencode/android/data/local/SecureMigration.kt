package com.opencode.android.data.local

/**
 * v3.2: 旧加密存储 → Tink 存储的迁移逻辑（纯函数，可 JVM 单测）。
 *
 * 语义：
 * - 逐 key 从 old 读明文 → 写 new → 回读校验；
 * - 任一 key 失败：清理本次已写入 new 的 key，回滚（old 原样保留），返回 Failure；
 * - 成功返回迁移的 key 列表；幂等——重复跑只补缺失 key。
 *
 * 旧文件不删除（保留至少 1 个版本，供回退与审计）。
 */
object SecureMigration {

    sealed class Result {
        data class Success(val migratedKeys: List<String>) : Result()
        data class Failure(val failedKey: String, val cause: Throwable) : Result()
    }

    fun migrate(old: SecureKvStore, new: SecureKvStore): Result {
        val written = mutableListOf<String>()
        return try {
            for (key in old.allKeys()) {
                if (new.get(key) != null) continue // 已迁移过，幂等跳过
                val value = old.get(key) ?: continue
                new.put(key, value)
                written.add(key)
                val verify = new.get(key)
                if (verify != value) {
                    throw IllegalStateException("回读校验不一致 key=$key")
                }
            }
            Result.Success(written)
        } catch (e: Exception) {
            // 清理本次部分写入，保持 new 干净；old 不动
            for (key in written) {
                try { new.remove(key) } catch (_: Exception) { }
            }
            val failedKey = written.lastOrNull() ?: "<unknown>"
            Result.Failure(failedKey, e)
        }
    }
}
