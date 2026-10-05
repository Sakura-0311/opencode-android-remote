package com.opencode.android.util

import org.junit.Assert.*
import org.junit.Test

/**
 * v4.3.2 A3: TargetSwitchPolicy 单测（纯 JVM）。
 * 对应 TESTING_CHECKLIST §8.5：切换目标电脑时有进行中会话需先弹确认。
 */
class TargetSwitchPolicyTest {

    @Test
    fun `generating requires confirm`() {
        assertTrue(TargetSwitchPolicy.needsConfirm(isGenerating = true, currentSessionId = ""))
    }

    @Test
    fun `active session requires confirm`() {
        assertTrue(TargetSwitchPolicy.needsConfirm(isGenerating = false, currentSessionId = "s-123"))
    }

    @Test
    fun `idle with no session switches directly`() {
        assertFalse(TargetSwitchPolicy.needsConfirm(isGenerating = false, currentSessionId = ""))
    }
}
