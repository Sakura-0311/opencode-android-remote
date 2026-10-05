package com.opencode.android.util

import com.opencode.android.data.model.ToolApprovalRequest
import org.junit.Assert.*
import org.junit.Test

/**
 * v4.3.2 A3: ApprovalReducer 单测（纯 JVM）。
 * 覆盖迭代方案 A3 路径 2：工具审批状态机（收到请求 → 同意/拒绝 → 弹窗消失）。
 */
class ApprovalReducerTest {

    private fun req(id: String) = ToolApprovalRequest(callId = id, toolName = "Edit", filePath = "/tmp/a.txt")

    @Test
    fun `requested sets pending`() {
        val r = req("c1")
        assertEquals(r, ApprovalReducer.reduce(null, ApprovalReducer.Event.Requested(r)))
    }

    @Test
    fun `approved clears pending`() {
        assertNull(ApprovalReducer.reduce(req("c1"), ApprovalReducer.Event.Approved))
    }

    @Test
    fun `rejected clears pending`() {
        assertNull(ApprovalReducer.reduce(req("c1"), ApprovalReducer.Event.Rejected))
    }

    @Test
    fun `approve and reject are idempotent on null`() {
        assertNull(ApprovalReducer.reduce(null, ApprovalReducer.Event.Approved))
        assertNull(ApprovalReducer.reduce(null, ApprovalReducer.Event.Rejected))
    }

    @Test
    fun `new request replaces existing pending`() {
        val first = req("c1")
        val second = req("c2")
        assertEquals(second, ApprovalReducer.reduce(first, ApprovalReducer.Event.Requested(second)))
    }
}
