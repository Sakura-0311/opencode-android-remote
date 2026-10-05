package com.opencode.android.util

/**
 * v4.3.2 A3: 目标电脑切换的确认决策（纯函数，可 JVM 单测）。
 *
 * 语义（与 OpenCodeViewModel.requestTargetSwitch 一致，对应 TESTING_CHECKLIST §8.5）：
 * 有进行中的会话（正在生成，或已选定会话）时切换目标电脑，先弹确认；
 * 空闲无会话时直接切换。
 */
object TargetSwitchPolicy {

    fun needsConfirm(isGenerating: Boolean, currentSessionId: String): Boolean =
        isGenerating || currentSessionId.isNotEmpty()
}
