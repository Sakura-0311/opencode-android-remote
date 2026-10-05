package com.opencode.android.coordinator

import com.opencode.android.data.model.MessageRole
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.DesktopInfo
import com.opencode.android.network.ProjectInfo
import org.junit.Assert.*
import org.junit.Test

/** 阶段 3: DeviceRoutingPrefs / DeviceRoutingSystem 的测试替身。 */
class FakeDeviceRoutingPrefs : DeviceRoutingPrefs {
    var savedTargetId: String? = null
    var favoriteProjectsValue: List<String> = emptyList()
    var savedFavorites: List<String>? = null

    override fun saveTargetDesktopId(deviceId: String) {
        savedTargetId = deviceId
    }

    override fun getFavoriteProjects(): List<String> = favoriteProjectsValue

    override fun saveFavoriteProjects(ids: List<String>) {
        savedFavorites = ids
    }
}

class FakeDeviceRoutingSystem : DeviceRoutingSystem {
    var stopProgressCalls = 0
    override fun stopTaskProgress() { stopProgressCalls++ }
}

/**
 * 阶段 3: DeviceRoutingCoordinator 单测（纯 JVM）。
 */
class DeviceRoutingCoordinatorTest {

    private data class Deps(
        val prefs: FakeDeviceRoutingPrefs,
        val system: FakeDeviceRoutingSystem,
        val dispatch: FakeStateDispatcher,
        val peerRefreshCalls: MutableList<Unit>,
    )

    private fun setup(
        state: OpenCodeUiState = OpenCodeUiState(),
        prefs: FakeDeviceRoutingPrefs = FakeDeviceRoutingPrefs(),
    ): Pair<DeviceRoutingCoordinator, Deps> {
        val system = FakeDeviceRoutingSystem()
        val dispatch = FakeStateDispatcher(state)
        val peerRefreshCalls = mutableListOf<Unit>()
        val c = DeviceRoutingCoordinator(
            prefs = prefs,
            system = system,
            dispatch = dispatch,
            onPeerRefresh = { peerRefreshCalls.add(Unit) },
            maxMessagesCount = 500,
        )
        return c to Deps(prefs, system, dispatch, peerRefreshCalls)
    }

    @Test fun onDesktopList_targetAlive_keptAndPeerRefreshed() {
        val (c, d) = setup(state = OpenCodeUiState(targetDesktopId = "d1"))
        c.onDesktopList(listOf(DesktopInfo(deviceId = "d1", deviceName = "n1")))

        val s = d.dispatch.currentState
        assertEquals("d1", s.targetDesktopId)
        assertEquals(listOf("d1"), s.desktopList.map { it.deviceId })
        assertNull(d.prefs.savedTargetId) // 存活不清空
        assertEquals(1, d.peerRefreshCalls.size)
    }

    @Test fun onDesktopList_targetGone_clearsTarget() {
        val (c, d) = setup(state = OpenCodeUiState(targetDesktopId = "gone"))
        c.onDesktopList(listOf(DesktopInfo(deviceId = "d1", deviceName = "n1", isPrimary = true)))

        assertEquals("", d.dispatch.currentState.targetDesktopId)
        assertEquals("", d.prefs.savedTargetId)
        assertEquals(1, d.peerRefreshCalls.size)
    }

    @Test fun onTargetDesktopOffline_buildsSystemMessage() {
        val (c, d) = setup(
            state = OpenCodeUiState(
                desktopList = listOf(DesktopInfo(deviceId = "d1", deviceName = "my-pc")),
                isGenerating = true,
            )
        )
        c.onTargetDesktopOffline("d1", "bye")

        assertEquals(1, d.system.stopProgressCalls)
        val s = d.dispatch.currentState
        assertFalse(s.isGenerating)
        assertEquals("DESKTOP_OFFLINE", s.appError?.code)
        assertNotNull(s.targetOfflineHint)
        assertEquals(s.targetOfflineHint, s.appError?.message) // 复用 appError 展示同一 hint
        assertEquals(1, s.messages.size)
        val msg = s.messages[0]
        assertEquals(MessageRole.SYSTEM, msg.role)
        assertTrue(msg.isError)
    }

    @Test fun onTargetDesktopOffline_unknownTarget_stillHandles() {
        val (c, d) = setup()
        c.onTargetDesktopOffline("unknown", "bye")
        val s = d.dispatch.currentState
        assertEquals("DESKTOP_OFFLINE", s.appError?.code)
        assertEquals(1, s.messages.size)
    }

    @Test fun toggleFavoriteProject_addsAndSaves() {
        val (c, d) = setup()
        c.toggleFavoriteProject("p1")
        assertEquals(setOf("p1"), d.dispatch.currentState.favoriteProjectIds)
        assertEquals(listOf("p1"), d.prefs.savedFavorites)
    }

    @Test fun toggleFavoriteProject_removesAndSaves() {
        val (c, d) = setup(state = OpenCodeUiState(favoriteProjectIds = setOf("p1", "p2")))
        c.toggleFavoriteProject("p1")
        assertEquals(setOf("p2"), d.dispatch.currentState.favoriteProjectIds)
        assertEquals(listOf("p2"), d.prefs.savedFavorites)
    }

    @Test fun onProjectsDataReceived_setsProjectsAndFavorites() {
        val prefs = FakeDeviceRoutingPrefs().apply { favoriteProjectsValue = listOf("p1") }
        val (c, d) = setup(prefs = prefs)
        val projects = listOf(ProjectInfo(id = "p1", name = "n1"))
        c.onProjectsDataReceived(projects, null)

        val s = d.dispatch.currentState
        assertEquals(projects, s.projects)
        assertNull(s.projectsError)
        assertEquals(setOf("p1"), s.favoriteProjectIds)
    }

    @Test fun onProjectsDataReceived_withError() {
        val (c, d) = setup()
        c.onProjectsDataReceived(emptyList(), "ERR")
        assertEquals("ERR", d.dispatch.currentState.projectsError)
    }
}
