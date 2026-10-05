package com.opencode.android.util

import com.opencode.android.data.model.SessionItem
import org.junit.Assert.*
import org.junit.Test

/**
 * B2: SessionReducer 单测（先补测试再拆）。
 * 全部纯函数，零 mock；now 显式传入保证确定性。
 */
class SessionReducerTest {

    private fun sess(id: String, pinned: Boolean = false, archived: Boolean = false,
                     updatedAt: Long = 1000L, tag: String = "default") =
        SessionItem(id = id, title = "t-$id", tag = tag,
            isPinned = pinned, isArchived = archived, updatedAt = updatedAt)

    @Test fun normalizeTagFilter_allBecomesNull() {
        assertNull(SessionReducer.normalizeTagFilter(TAG_ALL))
        assertEquals("work", SessionReducer.normalizeTagFilter("work"))
        assertNull(SessionReducer.normalizeTagFilter(null))
    }

    @Test fun togglePin_flipsOnlyTarget() {
        val list = listOf(sess("a"), sess("b", pinned = true))
        val out = SessionReducer.togglePin(list, "a", now = 2000L)
        assertTrue(out.first { it.id == "a" }.isPinned)
        assertEquals(2000L, out.first { it.id == "a" }.updatedAt)
        // b 不受影响
        assertTrue(out.first { it.id == "b" }.isPinned)
        assertEquals(1000L, out.first { it.id == "b" }.updatedAt)
        // 原列表不变（不可变性）
        assertFalse(list.first { it.id == "a" }.isPinned)
    }

    @Test fun togglePin_unknownId_noChange() {
        val list = listOf(sess("a"))
        assertEquals(list, SessionReducer.togglePin(list, "zzz", now = 2000L))
    }

    @Test fun archive_marksArchived() {
        val out = SessionReducer.archive(listOf(sess("a"), sess("b")), "b", now = 3000L)
        assertFalse(out.first { it.id == "a" }.isArchived)
        assertTrue(out.first { it.id == "b" }.isArchived)
        assertEquals(3000L, out.first { it.id == "b" }.updatedAt)
    }

    @Test fun batchArchive_skipsPinnedAndCurrent() {
        val list = listOf(
            sess("cur"),
            sess("pinned", pinned = true),
            sess("old1"),
            sess("old2", archived = true),  // 已归档的保持归档
        )
        val out = SessionReducer.batchArchive(list, currentSessionId = "cur")
        assertFalse(out.first { it.id == "cur" }.isArchived)
        assertFalse(out.first { it.id == "pinned" }.isArchived)
        assertTrue(out.first { it.id == "old1" }.isArchived)
        assertTrue(out.first { it.id == "old2" }.isArchived)
    }

    @Test fun batchArchive_nullCurrent_archivesAllUnpinned() {
        val list = listOf(sess("a"), sess("b", pinned = true))
        val out = SessionReducer.batchArchive(list, currentSessionId = null)
        assertTrue(out.first { it.id == "a" }.isArchived)
        assertFalse(out.first { it.id == "b" }.isArchived)
    }

    @Test fun setTag_updatesTagOnly() {
        val out = SessionReducer.setTag(listOf(sess("a", tag = "old")), "a", "work", now = 4000L)
        val s = out.single()
        assertEquals("work", s.tag)
        assertEquals(4000L, s.updatedAt)
    }

    @Test fun sortSessions_pinnedFirstThenUnarchivedThenNewest() {
        val list = listOf(
            sess("archived", archived = true, updatedAt = 9999L),
            sess("plain-old", updatedAt = 1000L),
            sess("pinned", pinned = true, updatedAt = 1000L),
            sess("plain-new", updatedAt = 5000L),
        )
        val ids = SessionReducer.sortSessions(list).map { it.id }
        assertEquals(listOf("pinned", "plain-new", "plain-old", "archived"), ids)
    }

    @Test fun sortSessions_empty_ok() {
        assertTrue(SessionReducer.sortSessions(emptyList()).isEmpty())
    }
}
