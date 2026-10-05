package com.opencode.android.coordinator

import com.opencode.android.R
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.ChatMessage
import com.opencode.android.data.model.MessageRole
import com.opencode.android.network.DesktopInfo
import com.opencode.android.network.DeviceInfo
import com.opencode.android.network.ProjectInfo
import com.opencode.android.util.AppLog
import com.opencode.android.util.DesktopRoutingPolicy
import com.opencode.android.util.DeviceListReducer
import com.opencode.android.util.ProjectsReducer
import java.util.UUID

/** 设备路由所需的 prefs 子集（窄接口，单测用假实现）。 */
interface DeviceRoutingPrefs {
    fun saveTargetDesktopId(deviceId: String)
    fun getFavoriteProjects(): List<String>
    fun saveFavoriteProjects(ids: List<String>)
}

/** 设备路由的系统副作用边界（窄接口，单测用假实现）。 */
interface DeviceRoutingSystem {
    fun stopTaskProgress()
}

/**
 * 阶段 3: 设备 / 桌面 / 项目相关回调的收拢。
 *
 * 把 ViewModel 的 onDeviceListReceived / onDesktopList / onTargetDesktopOffline /
 * onProjectsDataReceived / toggleFavoriteProject 迁入。核心决策在阶段 1 已抽为纯函数
 * （DesktopRoutingPolicy / DeviceListReducer / ProjectsReducer），这里只剩
 * 「回调 → 调 Reducer/纯函数 → 触副作用」的编排；AppLog 为纯 JVM 实现可直接调用。
 *
 * 行为约定：与迁移前逐行一致；ViewModel 保留同名方法做转发，UI 签名不变。
 */
class DeviceRoutingCoordinator(
    private val prefs: DeviceRoutingPrefs,
    private val system: DeviceRoutingSystem,
    private val dispatch: StateDispatcher,
    /** desktop 列表变化后刷新 E2EE 就绪态（生产侧指向 PairingCoordinator）。 */
    private val onPeerRefresh: () -> Unit,
    private val maxMessagesCount: Int,
) {
    fun onDeviceListReceived(devices: List<DeviceInfo>) {
        dispatch.updateState { it.copy(pairedDevices = devices) }
    }

    fun onDesktopList(desktops: List<DesktopInfo>) {
        val prevTarget = dispatch.currentState.targetDesktopId
        // 已选目标不在在线列表中 → 清空选择，回主 desktop 路由
        val (targetAlive, _) = DeviceListReducer.resolveTarget(prevTarget, desktops)
        if (!targetAlive) {
            prefs.saveTargetDesktopId("")
            AppLog.i("DesktopRouting", "target $prevTarget offline, fallback to primary")
        }
        dispatch.updateState { DeviceListReducer.applyDesktopList(it, desktops) }
        onPeerRefresh()
    }

    fun onTargetDesktopOffline(targetDeviceId: String, message: String) {
        system.stopTaskProgress()
        val targetName = dispatch.currentState.desktopList
            .firstOrNull { it.deviceId == targetDeviceId }
            ?.deviceName.orEmpty()
        val hint = DesktopRoutingPolicy.offlineHint(
            targetName, targetDeviceId, message,
            dispatch.getString(R.string.route_001),
            dispatch.getString(R.string.route_002),
            dispatch.getString(R.string.route_003)
        )
        AppLog.w("DesktopRouting", "target offline: $hint")
        dispatch.updateState { state ->
            val sysMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = dispatch.getString(R.string.vm_039, hint),
                isError = true
            )
            state.copy(
                messages = (state.messages + sysMsg).takeLast(maxMessagesCount),
                isGenerating = false,
                // 复用既有 appError 展示机制
                appError = AppError("DESKTOP_OFFLINE", hint),
                targetOfflineHint = hint
            )
        }
    }

    fun toggleFavoriteProject(projectId: String) {
        val updated = ProjectsReducer.toggleFavorite(dispatch.currentState.favoriteProjectIds, projectId)
        prefs.saveFavoriteProjects(updated.toList())
        dispatch.updateState { it.copy(favoriteProjectIds = updated) }
    }

    fun onProjectsDataReceived(projects: List<ProjectInfo>, projectsError: String?) {
        val favorites = prefs.getFavoriteProjects().toSet()
        dispatch.updateState { ProjectsReducer.applyProjectsData(it, projects, projectsError, favorites) }
    }
}
