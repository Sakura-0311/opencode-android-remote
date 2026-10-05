package com.opencode.android.coordinator

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.TaskStatus
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 2: TaskStatusController 单测（纯 JVM）。
 * 覆盖 6 种状态流转的持久化 + UI 断言（对应 docx 表 4），以及启动恢复。
 */
class TaskStatusControllerTest {

    private fun setup(
        state: OpenCodeUiState = OpenCodeUiState(),
        prefs: FakePairingPrefs = FakePairingPrefs(),
        dispatch: FakeStateDispatcher = FakeStateDispatcher(state),
    ): Triple<TaskStatusController, FakePairingPrefs, FakeStateDispatcher> =
        Triple(TaskStatusController(prefs, dispatch), prefs, dispatch)

    @Test fun set_running_persistsAndMarksGenerating() {
        val (controller, prefs, dispatch) = setup(state = OpenCodeUiState(currentSessionId = "sess-1"))
        controller.set(TaskStatus.RUNNING, "detail")

        // sessionId 为空时回退当前会话
        assertEquals(Triple("RUNNING", "detail", "sess-1"), prefs.savedTaskStatus)
        val s = dispatch.currentState
        assertEquals(TaskStatus.RUNNING, s.taskStatus)
        assertEquals("detail", s.taskStatusDetail)
        assertTrue(s.isGenerating)
        assertTrue(s.taskStartTimeMs > 0)
    }

    @Test fun set_explicitSessionId_used() {
        val (controller, prefs, _) = setup(state = OpenCodeUiState(currentSessionId = "sess-1"))
        controller.set(TaskStatus.RUNNING, "d", "sess-9")
        assertEquals(Triple("RUNNING", "d", "sess-9"), prefs.savedTaskStatus)
    }

    @Test fun set_completed_clearsGenerating() {
        val (controller, _, dispatch) = setup(state = OpenCodeUiState(isGenerating = true))
        controller.set(TaskStatus.COMPLETED, "done")
        assertFalse(dispatch.currentState.isGenerating)
        assertEquals(TaskStatus.COMPLETED, dispatch.currentState.taskStatus)
    }

    @Test fun set_failed_keepsNotGenerating() {
        val (controller, _, dispatch) = setup()
        controller.set(TaskStatus.FAILED, "err")
        val s = dispatch.currentState
        assertEquals(TaskStatus.FAILED, s.taskStatus)
        assertFalse(s.isGenerating)
    }

    @Test fun set_waitingInput_isGenerating() {
        val (controller, _, dispatch) = setup()
        controller.set(TaskStatus.WAITING_INPUT, "need input")
        assertTrue(dispatch.currentState.isGenerating)
    }

    @Test fun set_disconnected_notGenerating() {
        // docx 表 4: DISCONNECTED 任务态与连接态解耦
        val (controller, _, dispatch) = setup()
        controller.set(TaskStatus.DISCONNECTED, "reconnect")
        assertFalse(dispatch.currentState.isGenerating)
        assertEquals(TaskStatus.DISCONNECTED, dispatch.currentState.taskStatus)
    }

    // ============ restoreOnLaunch ============

    @Test fun restore_runningBecomesDisconnectedWithHint() {
        val prefs = FakePairingPrefs()
        prefs.taskStatusToRestore = Triple("RUNNING", "old detail", "sess-1")
        val dispatch = FakeStateDispatcher()
        val controller = TaskStatusController(prefs, dispatch)

        val (status, detail) = controller.restoreOnLaunch()
        assertEquals(TaskStatus.DISCONNECTED, status)
        assertTrue(detail.startsWith("s")) // getString(vm_001, savedDetail) 经假实现
        assertTrue(detail.contains("old detail"))
    }

    @Test fun restore_idleStaysIdleWithoutHint() {
        val prefs = FakePairingPrefs()
        prefs.taskStatusToRestore = Triple("IDLE", "", "")
        val controller = TaskStatusController(prefs, FakeStateDispatcher())

        val (status, detail) = controller.restoreOnLaunch()
        assertEquals(TaskStatus.IDLE, status)
        assertEquals("", detail)
    }

    @Test fun restore_garbageStatusFallsBackToIdle() {
        val prefs = FakePairingPrefs()
        prefs.taskStatusToRestore = Triple("NOT_A_STATUS", "x", "")
        val controller = TaskStatusController(prefs, FakeStateDispatcher())

        val (status, _) = controller.restoreOnLaunch()
        assertEquals(TaskStatus.IDLE, status)
    }

    @Test fun restore_completedKeepsDetailWithoutHint() {
        val prefs = FakePairingPrefs()
        prefs.taskStatusToRestore = Triple("COMPLETED", "done detail", "")
        val controller = TaskStatusController(prefs, FakeStateDispatcher())

        val (status, detail) = controller.restoreOnLaunch()
        assertEquals(TaskStatus.COMPLETED, status)
        assertEquals("done detail", detail) // 非中断恢复不拼提示文案
    }
}
