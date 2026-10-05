package com.opencode.android.network

import org.junit.Assert.*
import org.junit.Test

/**
 * RelaySeqTracker 单测（纯 JVM）：去重、track/flush、纪元重置、旧 relay 兼容。
 */
class RelaySeqTrackerTest {

    /** 内存版持久化。 */
    private class MemStore(var seq: Long = 0L, var epoch: String? = null) {
        fun tracker() = RelaySeqTracker(
            loadSeq = { seq },
            saveSeq = { seq = it },
            loadEpoch = { epoch },
            saveEpoch = { e, s -> epoch = e; seq = s },
        )
    }

    @Test fun duplicateDetection() {
        val t = MemStore().tracker()
        t.track(10L)
        assertTrue(t.isDuplicate(10L))
        assertTrue(t.isDuplicate(5L))
        assertFalse(t.isDuplicate(11L))
        assertFalse(t.isDuplicate(-1L)) // 无序号不算重复
    }

    @Test fun trackOnlyAdvances() {
        val t = MemStore().tracker()
        t.track(10L)
        t.track(5L) // 不回退
        assertEquals(10L, t.lastSeq)
    }

    @Test fun flushPersists() {
        val store = MemStore()
        val t = store.tracker()
        t.track(42L)
        t.flush()
        assertEquals(42L, store.seq)
        // 新 tracker reload 恢复
        val t2 = store.tracker()
        t2.reload()
        assertEquals(42L, t2.lastSeq)
    }

    @Test fun epochFirstSeen_recordsNoReset() {
        val store = MemStore()
        val t = store.tracker()
        t.track(100L)
        assertFalse(t.checkEpoch("epoch-a"))
        assertEquals("epoch-a", t.epoch)
        assertEquals(100L, t.lastSeq) // 不重置
        assertEquals("epoch-a", store.epoch)
    }

    @Test fun epochChanged_resetsSeq() {
        val store = MemStore()
        val t = store.tracker()
        t.checkEpoch("epoch-a")
        t.track(100L)
        t.flush()
        assertTrue(t.checkEpoch("epoch-b"))
        assertEquals("epoch-b", t.epoch)
        assertEquals(0L, t.lastSeq)
        assertEquals(0L, store.seq)
        assertEquals("epoch-b", store.epoch)
    }

    @Test fun epochUnchanged_noReset() {
        val t = MemStore().tracker()
        t.checkEpoch("epoch-a")
        t.track(50L)
        assertFalse(t.checkEpoch("epoch-a"))
        assertEquals(50L, t.lastSeq)
    }

    @Test fun blankEpoch_oldRelayKeepsBehavior() {
        val t = MemStore().tracker()
        t.checkEpoch("epoch-a")
        t.track(77L)
        assertFalse(t.checkEpoch(""))
        assertEquals(77L, t.lastSeq)
        assertEquals("epoch-a", t.epoch)
    }

    @Test fun reloadRestoresEpoch() {
        val store = MemStore()
        val t = store.tracker()
        t.checkEpoch("epoch-x")
        val t2 = store.tracker()
        t2.reload()
        assertEquals("epoch-x", t2.epoch)
    }
}
