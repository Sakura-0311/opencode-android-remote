package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P0: TaskStatusReducer 单测（先补测试再拆）。
 * 全部纯函数，零 mock；now 显式传入。
 */
class TaskStatusReducerTest {

    private val base = OpenCodeUiState()

    // ---- isGenerating: 7 种状态全覆盖（表 6 要求） ----

    @Test fun isGenerating_runningTrue() {
        assertTrue(TaskStatusReducer.isGenerating(TaskStatus.RUNNING))
    }

    @Test fun isGenerating_waitingInputTrue() {
        assertTrue(TaskStatusReducer.isGenerating(TaskStatus.WAITING_INPUT))
    }

    @Test fun isGenerating_approvalRequiredTrue() {
        assertTrue(TaskStatusReducer.isGenerating(TaskStatus.APPROVAL_REQUIRED))
    }

    @Test fun isGenerating_idleFailedCompletedDisconnectedFalse() {
        assertFalse(TaskStatusReducer.isGenerating(TaskStatus.IDLE))
        assertFalse(TaskStatusReducer.isGenerating(TaskStatus.FAILED))
        assertFalse(TaskStatusReducer.isGenerating(TaskStatus.COMPLETED))
        assertFalse(TaskStatusReducer.isGenerating(TaskStatus.DISCONNECTED))
    }

    // ---- nextTaskStartTimeMs ----

    @Test fun nextTaskStartTimeMs_runningTakesNow() {
        assertEquals(9999L, TaskStatusReducer.nextTaskStartTimeMs(TaskStatus.RUNNING, 9999L, 1000L))
    }

    @Test fun nextTaskStartTimeMs_othersKeepCurrent() {
        for (s in listOf(TaskStatus.IDLE, TaskStatus.FAILED, TaskStatus.COMPLETED,
            TaskStatus.DISCONNECTED, TaskStatus.WAITING_INPUT, TaskStatus.APPROVAL_REQUIRED)) {
            assertEquals(1000L, TaskStatusReducer.nextTaskStartTimeMs(s, 9999L, 1000L))
        }
    }

    // ---- applyTaskStatus ----

    @Test fun applyTaskStatus_runningSetsGeneratingAndTime() {
        val out = TaskStatusReducer.applyTaskStatus(base, TaskStatus.RUNNING, "hi", 5000L)
        assertEquals(TaskStatus.RUNNING, out.taskStatus)
        assertEquals("hi", out.taskStatusDetail)
        assertEquals(5000L, out.taskStartTimeMs)
        assertTrue(out.isGenerating)
        // 原对象不变
        assertEquals(TaskStatus.IDLE, base.taskStatus)
        assertEquals(0L, base.taskStartTimeMs)
    }

    @Test fun applyTaskStatus_failedKeepsOldTime() {
        val started = base.copy(taskStartTimeMs = 1234L)
        val out = TaskStatusReducer.applyTaskStatus(started, TaskStatus.FAILED, "boom", 9999L)
        assertEquals(1234L, out.taskStartTimeMs)
        assertFalse(out.isGenerating)
        assertEquals("boom", out.taskStatusDetail)
    }

    // ---- restoreOnLaunch ----

    @Test fun restoreOnLaunch_runningBecomesDisconnected() {
        assertEquals(TaskStatus.DISCONNECTED, TaskStatusReducer.restoreOnLaunch(TaskStatus.RUNNING))
    }

    @Test fun restoreOnLaunch_waitingInputBecomesDisconnected() {
        assertEquals(TaskStatus.DISCONNECTED, TaskStatusReducer.restoreOnLaunch(TaskStatus.WAITING_INPUT))
    }

    @Test fun restoreOnLaunch_approvalRequiredBecomesDisconnected() {
        assertEquals(TaskStatus.DISCONNECTED, TaskStatusReducer.restoreOnLaunch(TaskStatus.APPROVAL_REQUIRED))
    }

    @Test fun restoreOnLaunch_terminalStatesUnchanged() {
        assertEquals(TaskStatus.IDLE, TaskStatusReducer.restoreOnLaunch(TaskStatus.IDLE))
        assertEquals(TaskStatus.FAILED, TaskStatusReducer.restoreOnLaunch(TaskStatus.FAILED))
        assertEquals(TaskStatus.COMPLETED, TaskStatusReducer.restoreOnLaunch(TaskStatus.COMPLETED))
        assertEquals(TaskStatus.DISCONNECTED, TaskStatusReducer.restoreOnLaunch(TaskStatus.DISCONNECTED))
    }

    // ---- needsRestoreHint ----

    @Test fun needsRestoreHint_trueWhenDisconnectedFromNonIdle() {
        assertTrue(TaskStatusReducer.needsRestoreHint(TaskStatus.DISCONNECTED, TaskStatus.RUNNING))
    }

    @Test fun needsRestoreHint_falseWhenWasIdle() {
        assertFalse(TaskStatusReducer.needsRestoreHint(TaskStatus.DISCONNECTED, TaskStatus.IDLE))
    }

    @Test fun needsRestoreHint_falseWhenNotDisconnected() {
        assertFalse(TaskStatusReducer.needsRestoreHint(TaskStatus.FAILED, TaskStatus.RUNNING))
    }
}
