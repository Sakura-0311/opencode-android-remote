package com.opencode.android.util

import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.CloudConnectionState
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P1: StreamEndReducer 单测（先补测试再拆）。
 */
class StreamEndReducerTest {

    private val base = OpenCodeUiState()

    private fun msg(id: String, streaming: Boolean) =
        ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "x", isStreaming = streaming)

    @Test fun finalize_onlyActiveMarkedDone() {
        val list = listOf(msg("a", true), msg("b", true), msg("c", false))
        val out = StreamEndReducer.finalizeMessages(list, "b")
        assertTrue(out.first { it.id == "a" }.isStreaming)
        assertFalse(out.first { it.id == "b" }.isStreaming)
        assertFalse(out.first { it.id == "c" }.isStreaming)
        // 原列表不变
        assertTrue(list.first { it.id == "b" }.isStreaming)
    }

    @Test fun finalize_nullActiveId_noChange() {
        val list = listOf(msg("a", true))
        val out = StreamEndReducer.finalizeMessages(list, null)
        assertTrue(out[0].isStreaming)
    }

    @Test fun finalize_unknownId_noChange() {
        val list = listOf(msg("a", true))
        val out = StreamEndReducer.finalizeMessages(list, "zzz")
        assertTrue(out[0].isStreaming)
    }

    @Test fun applyStreamEnd_fullState() {
        val state = base.copy(
            cloudConnectionState = CloudConnectionState.STREAMING,
            messages = listOf(msg("a", true)),
            isGenerating = true
        )
        val out = StreamEndReducer.applyStreamEnd(state, "a")
        assertEquals(CloudConnectionState.DISCONNECTED, out.cloudConnectionState)
        assertFalse(out.isGenerating)
        assertFalse(out.messages[0].isStreaming)
        assertTrue(state.isGenerating) // 不变性
    }

    @Test fun computeDuration_normal() {
        assertEquals(1500L, StreamEndReducer.computeDurationMs(1000L, 2500L))
    }

    @Test fun computeDuration_zeroStart_returnsZero() {
        assertEquals(0L, StreamEndReducer.computeDurationMs(0L, 2500L))
    }

    @Test fun computeDuration_negativeStart_returnsZero() {
        assertEquals(0L, StreamEndReducer.computeDurationMs(-5L, 2500L))
    }
}
