package com.opencode.android.util

import com.opencode.android.R
import androidx.annotation.StringRes

/**
 * v2.5: 错误码 →（标题，原因，建议）映射表。
 * 隧道类文案复用 TunnelDiagnosticsHelper 的 Cloudflare / SakuraFrp 排障口径。
 */
data class ErrorInfo(
    @StringRes val titleRes: Int,
    @StringRes val reasonRes: Int,
    @StringRes val suggestionRes: Int
)

object ErrorCodes {

    private val TABLE: Map<String, ErrorInfo> = mapOf(
        "AUTH_FAILED" to ErrorInfo(
            R.string.err_001,
            R.string.err_002,
            R.string.err_003,
        "DEVICE_REVOKED" to ErrorInfo(
            R.string.err_004,
            R.string.err_005,
            R.string.err_006,
        "RESYNC_REQUIRED" to ErrorInfo(
            R.string.err_007,
            R.string.err_008,
            R.string.err_009,
        "PROTOCOL_MISMATCH" to ErrorInfo(
            R.string.err_010,
            R.string.err_011,
            R.string.err_012,
        "DESKTOP_OFFLINE" to ErrorInfo(
            R.string.err_013,
            R.string.err_014,
            R.string.err_015,
        "OPENCODE_UNREACHABLE" to ErrorInfo(
            R.string.err_016,
            R.string.err_017,
            R.string.err_018,
        "PATH_NOT_ALLOWED" to ErrorInfo(
            R.string.err_019,
            R.string.err_020,
            R.string.err_021,
        "SEND_UNCONFIRMED" to ErrorInfo(
            R.string.err_022,
            R.string.err_023,
            R.string.err_024,
        "SSE_RETRY_EXHAUSTED" to ErrorInfo(
            R.string.err_025,
            R.string.err_026,
            R.string.err_027,
        "CREATE_SESSION_FAILED" to ErrorInfo(
            R.string.err_028,
            R.string.err_029,
            R.string.err_030,
        "SECURE_STORAGE_UNAVAILABLE" to ErrorInfo(
            R.string.err_031,
            R.string.err_032,
            R.string.err_033,
        "NETWORK_ERROR" to ErrorInfo(
            R.string.err_034,
            R.string.err_035,
            R.string.err_036,
        "FILE_LIST_ERROR" to ErrorInfo(
            R.string.err_037,
            R.string.err_038,
            R.string.err_039,
        // 隧道类：复用 TunnelDiagnosticsHelper 口径
        "HTTP_521" to ErrorInfo(
            R.string.err_040,
            R.string.err_041,
            R.string.err_042,
        "HTTP_522" to ErrorInfo(
            R.string.err_043,
            R.string.err_044,
            R.string.err_045,
        "HTTP_520" to ErrorInfo(
            R.string.err_046,
            R.string.err_047,
            R.string.err_048,
        "HTTP_524" to ErrorInfo(
            R.string.err_049,
            R.string.err_050,
            R.string.err_051,
        "CF_ZERO_TRUST" to ErrorInfo(
            R.string.err_052,
            R.string.err_053,
            R.string.err_054,
        "SAKURAFRP_ERROR" to ErrorInfo(
            R.string.err_055,
            R.string.err_056,
            R.string.err_057
    )

    /** 未知错误码回退为通用条目，不抛异常。 */
    fun lookup(code: String): ErrorInfo =
        TABLE[code] ?: ErrorInfo(
            titleRes = R.string.err_058,
            reasonRes = R.string.err_059,
            suggestionRes = R.string.err_060
        )
}
