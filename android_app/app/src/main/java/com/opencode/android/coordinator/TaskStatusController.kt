package com.opencode.android.coordinator

import com.opencode.android.R
import com.opencode.android.data.model.TaskStatus
import com.opencode.android.util.TaskStatusReducer

/** setTaskStatus 所需的 prefs 子集（窄接口，单测用假实现）。 */
interface TaskStatusPrefs {
    fun saveTaskStatus(status: String, detail: String, sessionId: String)
    fun getTaskStatus(): Triple<String, String, String>
}

/**
 * 阶段 2: 收拢任务状态流转（同步 UI + 持久化）。
 *
 * 原 ViewModel.setTaskStatus 与 init 中的启动恢复逻辑迁入此。
 * ViewModel 保留同名私有方法做转发，调用点签名不变。
 */
class TaskStatusController(
    private val prefs: TaskStatusPrefs,
    private val dispatch: StateDispatcher,
) {
    /**
     * v1.6 P0 后台保活：统一任务状态流转（同步 UI + 持久化）。
     * sessionId 为空时回退到当前会话。
     */
    fun set(status: TaskStatus, detail: String = "", sessionId: String = "") {
        val sid = sessionId.ifBlank { dispatch.currentState.currentSessionId }
        prefs.saveTaskStatus(status.name, detail, sid)
        val now = System.currentTimeMillis()
        dispatch.updateState { TaskStatusReducer.applyTaskStatus(it, status, detail, now) }
    }

    /**
     * App 启动恢复：读存档状态；若上次退出时任务还在进行中，
     * 标记为 DISCONNECTED（需重连同步）并拼恢复提示文案，而非假装仍在运行。
     *
     * 返回 (effectiveStatus, effectiveDetail)，调用方负责写入初始 State。
     * 注意：不读 dispatch.currentState（init 时 StateFlow 尚未创建），只读 prefs。
     */
    fun restoreOnLaunch(): Pair<TaskStatus, String> {
        val (savedStatus, savedDetail, _) = prefs.getTaskStatus()
        val restoredStatus = try {
            TaskStatus.valueOf(savedStatus)
        } catch (e: Exception) { TaskStatus.IDLE }
        val effectiveStatus = TaskStatusReducer.restoreOnLaunch(restoredStatus)
        val effectiveDetail = if (TaskStatusReducer.needsRestoreHint(effectiveStatus, restoredStatus)) {
            dispatch.getString(R.string.vm_001, savedDetail)
        } else savedDetail
        return effectiveStatus to effectiveDetail
    }
}
