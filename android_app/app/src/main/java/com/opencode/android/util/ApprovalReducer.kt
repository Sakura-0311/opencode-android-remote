package com.opencode.android.util

import com.opencode.android.data.model.ToolApprovalRequest

/**
 * v4.3.2 A3: 工具审批状态机的纯状态转移（无 Android 依赖，可 JVM 单测）。
 *
 * 语义（与 OpenCodeViewModel 一致）：
 * - Requested(request)：pendingApproval 置为新请求（若已有未处理的请求，直接替换）；
 * - Approved / Rejected：pendingApproval 清空（无请求时保持 null，幂等）。
 *
 * 网络回传、OpLog、通知等副作用仍在 ViewModel 层处理，这里只做纯状态转移。
 */
object ApprovalReducer {

    sealed interface Event {
        data class Requested(val request: ToolApprovalRequest) : Event
        data object Approved : Event
        data object Rejected : Event
    }

    fun reduce(pending: ToolApprovalRequest?, event: Event): ToolApprovalRequest? =
        when (event) {
            is Event.Requested -> event.request
            Event.Approved -> null
            Event.Rejected -> null
        }
}
