package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.TaskStatus

/**
 * 阶段 1/P0: 任务状态纯变换（从 OpenCodeViewModel.setTaskStatus + init 恢复逻辑抽出）。
 *
 * 约定（沿用 SessionReducer 范式）：
 * - now 显式传入，不碰 System.currentTimeMillis()；
 * - 不碰 prefsManager 持久化、不碰 Context/getString；
 * - 返回新对象，不修改入参。
 */
object TaskStatusReducer {

    /** 哪些任务状态算"生成中"（UI 转圈 + 通知栏计时） */
    fun isGenerating(status: TaskStatus): Boolean =
        status == TaskStatus.RUNNING
            || status == TaskStatus.WAITING_INPUT
            || status == TaskStatus.APPROVAL_REQUIRED

    /** RUNNING 开始计时，其余状态保持原时间戳 */
    fun nextTaskStartTimeMs(status: TaskStatus, now: Long, current: Long): Long =
        if (status == TaskStatus.RUNNING) now else current

    /** setTaskStatus 的纯状态部分：持久化（prefsManager.saveTaskStatus）仍留在 ViewModel */
    fun applyTaskStatus(
        state: OpenCodeUiState,
        status: TaskStatus,
        detail: String,
        now: Long
    ): OpenCodeUiState = state.copy(
        taskStatus = status,
        taskStatusDetail = detail,
        taskStartTimeMs = nextTaskStartTimeMs(status, now, state.taskStartTimeMs),
        isGenerating = isGenerating(status)
    )

    /**
     * App 重启恢复：上次退出时还在进行中的任务，标记为 DISCONNECTED（需重连同步），
     * 而不是假装仍在运行。v1.6 P0 后台保活语义。
     */
    fun restoreOnLaunch(saved: TaskStatus): TaskStatus =
        if (saved == TaskStatus.RUNNING
            || saved == TaskStatus.WAITING_INPUT
            || saved == TaskStatus.APPROVAL_REQUIRED
        ) TaskStatus.DISCONNECTED else saved

    /** 恢复时是否需要拼接"上次异常退出"提示文案（文案本身由 ViewModel 经 getString 组装） */
    fun needsRestoreHint(effective: TaskStatus, restored: TaskStatus): Boolean =
        effective == TaskStatus.DISCONNECTED && restored != TaskStatus.IDLE
}
