package com.opencode.android.util

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.DesktopInfo

/**
 * 阶段 1/P1: 设备/桌面列表纯变换（从 onDesktopList + refreshE2eePeerReady 抽出）。
 *
 * prefsManager 落盘、AppLog、e2eeManager.hasPeerKey 留在 ViewModel；
 * 这里只做"目标存活判定 + 列表合并 + E2EE 目标选择"。
 */
object DeviceListReducer {

    /**
     * 已选目标是否仍在在线列表中。
     * @return Pair(存活与否, 生效后的 targetDesktopId：不存活则置空回主路由）
     */
    fun resolveTarget(
        prevTarget: String,
        desktops: List<DesktopInfo>
    ): Pair<Boolean, String> {
        val alive = prevTarget.isEmpty() || desktops.any { it.deviceId == prevTarget }
        return alive to if (alive) prevTarget else ""
    }

    /** onDesktopList 的纯状态部分（targetOffline 的落盘与日志留在 ViewModel） */
    fun applyDesktopList(
        state: OpenCodeUiState,
        desktops: List<DesktopInfo>
    ): OpenCodeUiState {
        val (_, target) = resolveTarget(state.targetDesktopId, desktops)
        return state.copy(
            desktopList = desktops,
            targetDesktopId = target
        )
    }

    /**
     * v4.3 M-5: E2EE 就绪检查的目标选择。
     * 显式目标为空时回退到主 desktop（isPrimary），都没有则返回空。
     */
    fun selectE2eeTarget(
        targetDesktopId: String,
        desktops: List<DesktopInfo>
    ): String =
        targetDesktopId.ifEmpty {
            desktops.firstOrNull { it.isPrimary }?.deviceId.orEmpty()
        }
}
