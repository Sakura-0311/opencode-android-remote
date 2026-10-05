package com.opencode.android.util

import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.OpenCodeUiState

/**
 * 阶段 1/P0: 配对链路纯状态变换（从 claimPairingByQr + pairDesktop 抽出）。
 *
 * 这条链路是历史 bug 集中区（v4.1~v4.3.2 双实例 keyset 竞争），优先纳入测试保护。
 * 约定：
 * - 所有 getString 文案由 ViewModel 传入（红线：不把 getString 挪进 Reducer）；
 * - 网络调用（PairingClient）、加密存储（prefsManager）、E2EE 密钥操作、OpLog 留在 ViewModel；
 * - 只做"纯状态变换"：输入当前 UiState + 已知结果 → 输出新 UiState。
 */
object PairingStateReducer {

    /** 手动配对输入校验结果（文案由 ViewModel 按分支取 vm_014/vm_015） */
    enum class ManualInputError { NONE, BLANK_ACCOUNT, BLANK_SECRET }

    fun validateManualInput(accountId: String, secret: String): ManualInputError = when {
        accountId.trim().isBlank() -> ManualInputError.BLANK_ACCOUNT
        secret.trim().isBlank() -> ManualInputError.BLANK_SECRET
        else -> ManualInputError.NONE
    }

    /** 扫码认领开始：banner 提示 + 清错误 */
    fun applyClaimStarted(state: OpenCodeUiState, banner: String): OpenCodeUiState =
        state.copy(statusBanner = banner, appError = null)

    /** v4.3 M-2: E2EE 对端公钥存储结果 → 一次性通道提示 或 公钥不可信错误 */
    fun applyE2eePubkeyResult(
        state: OpenCodeUiState,
        storeOk: Boolean,
        untrustedMessage: String
    ): OpenCodeUiState =
        if (storeOk) state.copy(showE2eeChannelDialog = true)
        else state.copy(appError = AppError("E2EE_PUBKEY_UNTRUSTED", untrustedMessage))

    /**
     * 扫码认领成功：凭据落盘成功后的 UiState（落盘本身留在 ViewModel）。
     * successBanner / accountId 已由调用方做 ifBlank 回退。
     */
    fun applyClaimSuccess(
        state: OpenCodeUiState,
        accountId: String,
        deviceSecret: String,
        relayUrl: String,
        successBanner: String
    ): OpenCodeUiState = state.copy(
        appMode = AppMode.DESKTOP_RELAY,
        accountId = accountId,
        secret = deviceSecret,
        relayUrl = relayUrl,
        isPaired = true,
        isAuthenticated = false,
        appError = null,
        statusBanner = successBanner
    )

    /** 扫码认领失败：PAIR_FAILED */
    fun applyClaimFailed(state: OpenCodeUiState, error: String): OpenCodeUiState =
        state.copy(appError = AppError("PAIR_FAILED", error), statusBanner = null)

    /** 凭据落盘失败：加密存储不可用 */
    fun applySaveFailed(
        state: OpenCodeUiState,
        secureUnavailableMessage: String
    ): OpenCodeUiState = state.copy(
        appError = AppError("SECURE_STORAGE_UNAVAILABLE", secureUnavailableMessage),
        statusBanner = null
    )

    /** 手动配对成功：与扫码成功同构（secret 为房间主 secret），多清 diagnostics */
    fun applyManualSuccess(
        state: OpenCodeUiState,
        accountId: String,
        secret: String,
        relayUrl: String,
        successBanner: String
    ): OpenCodeUiState = state.copy(
        appMode = AppMode.DESKTOP_RELAY,
        accountId = accountId,
        secret = secret,
        relayUrl = relayUrl,
        isPaired = true,
        isAuthenticated = false,
        appError = null,
        diagnostics = null,
        statusBanner = successBanner
    )
}
