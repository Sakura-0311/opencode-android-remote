package com.opencode.android.util

import com.opencode.android.data.model.SessionItem

/**
 * B2: 会话列表纯状态变换（从 OpenCodeViewModel 抽出）。
 *
 * 原 ViewModel 的 setTagFilter/togglePinSession/archiveSession/batchArchiveOldSessions/
 * setSessionTag/sortSessions 都是纯数据变换 + 持久化；这里只保留纯变换部分，
 * 持久化（prefsManager.saveSessions）与 StateFlow 更新仍留在 ViewModel。
 * now 参数显式传入，保证可测试性（原代码在 lambda 内调 System.currentTimeMillis()）。
 */
object SessionReducer {

    /** v4.3 M-4: TAG_ALL 视为不过滤 */
    fun normalizeTagFilter(tag: String?): String? =
        if (tag == TAG_ALL) null else tag

    fun togglePin(sessions: List<SessionItem>, sessionId: String, now: Long): List<SessionItem> =
        sessions.map { s ->
            if (s.id == sessionId) s.copy(isPinned = !s.isPinned, updatedAt = now) else s
        }

    fun archive(sessions: List<SessionItem>, sessionId: String, now: Long): List<SessionItem> =
        sessions.map { s ->
            if (s.id == sessionId) s.copy(isArchived = true, updatedAt = now) else s
        }

    fun batchArchive(sessions: List<SessionItem>, currentSessionId: String?): List<SessionItem> =
        sessions.map { s ->
            if (!s.isPinned && s.id != currentSessionId) s.copy(isArchived = true) else s
        }

    fun setTag(sessions: List<SessionItem>, sessionId: String, newTag: String, now: Long): List<SessionItem> =
        sessions.map { s ->
            if (s.id == sessionId) s.copy(tag = newTag, updatedAt = now) else s
        }

    /** 置顶优先，其次未归档优先，最后按更新时间倒序 */
    fun sortSessions(sessions: List<SessionItem>): List<SessionItem> =
        sessions.sortedWith(
            compareByDescending<SessionItem> { it.isPinned }
                .thenBy { it.isArchived }
                .thenByDescending { it.updatedAt }
        )
}
