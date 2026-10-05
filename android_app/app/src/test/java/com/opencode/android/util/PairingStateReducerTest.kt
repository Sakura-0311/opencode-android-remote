package com.opencode.android.util

import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.util.PairingStateReducer.ManualInputError
import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段 1/P0: PairingStateReducer 单测（先补测试再拆）。
 * 配对链路是历史 bug 集中区，优先保护。
 */
class PairingStateReducerTest {

    private val base = OpenCodeUiState()

    // ---- validateManualInput ----

    @Test fun validate_blankAccount() {
        assertEquals(ManualInputError.BLANK_ACCOUNT, PairingStateReducer.validateManualInput("  ", "s"))
    }

    @Test fun validate_blankSecret() {
        assertEquals(ManualInputError.BLANK_SECRET, PairingStateReducer.validateManualInput("a", "   "))
    }

    @Test fun validate_okTrims() {
        assertEquals(ManualInputError.NONE, PairingStateReducer.validateManualInput("  a ", " s "))
    }

    // ---- applyClaimStarted ----

    @Test fun claimStarted_setsBannerClearsError() {
        val withErr = base.copy(appError = com.opencode.android.data.model.AppError("X", "y"))
        val out = PairingStateReducer.applyClaimStarted(withErr, "配对中…")
        assertEquals("配对中…", out.statusBanner)
        assertNull(out.appError)
        assertNotNull(withErr.appError) // 原对象不变
    }

    // ---- applyE2eePubkeyResult ----

    @Test fun e2eeResult_okShowsChannelDialog() {
        val out = PairingStateReducer.applyE2eePubkeyResult(base, true, "untrusted")
        assertTrue(out.showE2eeChannelDialog)
        assertNull(out.appError)
    }

    @Test fun e2eeResult_failSetsUntrustedError() {
        val out = PairingStateReducer.applyE2eePubkeyResult(base, false, "UNTRUSTED_MSG")
        assertFalse(out.showE2eeChannelDialog)
        assertEquals("E2EE_PUBKEY_UNTRUSTED", out.appError?.code)
        assertEquals("UNTRUSTED_MSG", out.appError?.message)
    }

    // ---- applyClaimSuccess ----

    @Test fun claimSuccess_fullState() {
        val out = PairingStateReducer.applyClaimSuccess(base, "acc", "dev-secret", "http://r", "配对成功")
        assertEquals(AppMode.DESKTOP_RELAY, out.appMode)
        assertEquals("acc", out.accountId)
        assertEquals("dev-secret", out.secret)
        assertEquals("http://r", out.relayUrl)
        assertTrue(out.isPaired)
        assertFalse(out.isAuthenticated)
        assertNull(out.appError)
        assertEquals("配对成功", out.statusBanner)
        // 原对象不变
        assertFalse(base.isPaired)
    }

    // ---- applyClaimFailed ----

    @Test fun claimFailed_setsPairFailed() {
        val out = PairingStateReducer.applyClaimFailed(base.copy(statusBanner = "x"), "ERR")
        assertEquals("PAIR_FAILED", out.appError?.code)
        assertEquals("ERR", out.appError?.message)
        assertNull(out.statusBanner)
    }

    // ---- applySaveFailed ----

    @Test fun saveFailed_secureStorageUnavailable() {
        val out = PairingStateReducer.applySaveFailed(base, "SEC_MSG")
        assertEquals("SECURE_STORAGE_UNAVAILABLE", out.appError?.code)
        assertEquals("SEC_MSG", out.appError?.message)
        assertNull(out.statusBanner)
    }

    // ---- applyManualSuccess ----

    @Test fun manualSuccess_clearsDiagnostics() {
        val withDiag = base.copy(diagnostics = com.opencode.android.data.model.DiagnosticsResult(
            isSuccess = false, detailMessage = "m"))
        val out = PairingStateReducer.applyManualSuccess(withDiag, "a", "s", "r", "ok")
        assertNull(out.diagnostics)
        assertEquals(AppMode.DESKTOP_RELAY, out.appMode)
        assertTrue(out.isPaired)
    }
}
