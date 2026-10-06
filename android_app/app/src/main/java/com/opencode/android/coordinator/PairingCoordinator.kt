package com.opencode.android.coordinator

import com.opencode.android.R
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.SessionItem
import com.opencode.android.network.PairClaimResult
import com.opencode.android.network.TransportParams
import com.opencode.android.util.CloudPairReducer
import com.opencode.android.util.DeviceListReducer
import com.opencode.android.util.PairingStateReducer
import com.opencode.android.util.PairingStateReducer.ManualInputError

/** 配对链路所需的 prefs 子集（窄接口，单测用假实现）。 */
interface PairingPrefs {
    var secretIsMaster: Boolean
    fun savePairingInfo(accountId: String, secret: String, relayUrl: String): Boolean
    fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String): Boolean
    // v4.6.0: 存本机 relay device_id（E2EE AAD sender）
    fun setE2eeOwnRelayDeviceId(id: String)
}

/** 配对链路所需的 E2EE 子集（窄接口，单测用假实现）。 */
interface PairingE2ee {
    fun ownPublicKeyB64(): String?
    fun storePeerPubkey(deviceId: String, pubkeyB64: String, sig: String): Boolean
    fun hasPeerKey(deviceId: String): Boolean
    // v5.0.3 (A-1): 最近一次协商成功的对端。默认实现让既有假实现无需改动。
    fun lastNegotiatedPeerId(): String? = null
}

/**
 * 配对链路的网络 / 系统副作用边界（窄接口，单测用假实现）。
 * 生产实现负责消化 Context（见 ViewModelPairingTransports）。
 */
interface PairingTransports {
    /** 原 PairingClient.claimPairing 静态 suspend 调用；抛异常表示网络层失败。 */
    suspend fun claimPairing(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        e2eePubkey: String
    ): PairClaimResult

    /** 原 relayTransport.connect(params, transportListener)。 */
    fun connectRelay(params: TransportParams)

    /** 原 relayClient.disconnect()。 */
    fun disconnectRelay()

    /** 原 cloudClient.checkHealth(url, key, callback)。 */
    fun checkCloudHealth(cloudUrl: String, apiKey: String, callback: (Boolean, String) -> Unit)

    /** 原 cloudClient.getSessions(url, key, callback)。 */
    fun getCloudSessions(cloudUrl: String, apiKey: String, callback: (List<SessionItem>) -> Unit)

    /** 原 cloudClient.cancelCurrentStream()。 */
    fun cancelCloudStream()

    /** 原 OpenCodeKeepAliveService.stopTaskProgress(context)。 */
    fun stopTaskProgress()

    /** 原 OpLog.record(context, OpType.PAIR, detail)。 */
    fun logPair(detail: String)
}

/**
 * 阶段 2: 配对链路唯一入口。
 *
 * 把 ViewModel 的 claimPairingByQr / pairDesktop / pairCloud / unpair
 * 四个方法（约 185 行）原样迁入。所有副作用收拢在 [PairingPrefs] /
 * [PairingE2ee] / [PairingTransports] 三个窄接口之后，状态调度走
 * [StateDispatcher]——因此可在纯 JVM 单测里验证并发与失败路径。
 *
 * 行为约定：与迁移前逐行一致；ViewModel 保留同名方法做转发，UI 签名不变。
 *
 * 并发安全：prefs 凭据写与配对状态更新在 [pairingLock] 下原子完成——并发配对
 * （如双击配对按钮）不会出现「prefs 存 A、state 显示 B」的不一致。
 * 临界区内只有快速操作（prefs apply / state 更新 / 内存 E2EE），网络 I/O
 * 始终在锁外，因此可用 JVM monitor，不会阻塞主线程。
 */
class PairingCoordinator(
    private val prefs: PairingPrefs,
    private val e2ee: PairingE2ee,
    private val transports: PairingTransports,
    private val dispatch: StateDispatcher,
) {
    /** 配对链路串行锁，见类 KDoc。 */
    private val pairingLock = Any()
    /**
     * v1.6 P0 一键扫码配对：凭扫码得到的配对信息认领设备密钥。
     * 成功后凭据保存到加密存储（Android Keystore），之后用设备密钥连接。
     */
    fun claimPairingByQr(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        desktopName: String,
        onDone: (Boolean, String) -> Unit
    ) {
        dispatch.launch {
            dispatch.updateState {
                PairingStateReducer.applyClaimStarted(
                    it,
                    dispatch.getString(R.string.vm_007, desktopName)
                )
            }
            // v4.1: E2EE 公钥交换（开关关闭时传空，relay/对端跳过）
            val e2eePubkey = e2ee.ownPublicKeyB64() ?: ""
            val result = try {
                transports.claimPairing(relayUrl, accountId, pairingToken, e2eePubkey)
            } catch (e: Exception) {
                PairClaimResult(success = false, error = e.message ?: dispatch.getString(R.string.vm_008))
            }
            // 临界区：E2EE 存储 + 凭据保存 + 状态更新原子完成（与 pairDesktop/pairCloud 互斥）
            synchronized(pairingLock) {
                // v4.6.0: 存本机 relay device_id（E2EE AAD sender 用它，与 desktop 侧 peer id 一致）
                if (result.success && result.deviceId.isNotBlank()) {
                    prefs.setE2eeOwnRelayDeviceId(result.deviceId)
                }
                // v4.3 M-2: 保存 desktop 的 E2EE 公钥（按 device_id 绑定，HMAC 验签）
                if (result.success && result.e2eePeerPubkey.isNotEmpty() && result.desktopDeviceId.isNotEmpty()) {
                    val ok = e2ee.storePeerPubkey(
                        result.desktopDeviceId, result.e2eePeerPubkey, result.e2eePubkeySig)
                    if (!ok) {
                        dispatch.updateState {
                            // v5.0.2: 文案走资源。本文件本来就用 dispatch.getString(...)，
                            // 这一处是漏用（Reducer 的红线是「不把 getString 挪进去」，
                            // 文案由调用方取好传入——这里照做即可）。
                            PairingStateReducer.applyE2eePubkeyResult(
                                it, false, dispatch.getString(R.string.vm_n01)
                            )
                        }
                    } else {
                        // v4.3 M-5: 新配对成功，给一次「已建立加密通道」明确提示
                        dispatch.updateState { PairingStateReducer.applyE2eePubkeyResult(it, true, "") }
                    }
                    refreshE2eePeerReady()
                }
                if (result.success && result.deviceSecret.isNotBlank()) {
                    // v1.6: 设备密钥保存到加密存储；P0-3: 加密不可用时拒绝保存并报错
                    // v4.3 M-2: 扫码配对存的是 device_secret（非主 secret），标记之
                    prefs.secretIsMaster = false
                    val saved = prefs.savePairingInfo(
                        result.accountId.ifBlank { accountId },
                        result.deviceSecret,
                        relayUrl
                    )
                    if (!saved) {
                        dispatch.updateState {
                            PairingStateReducer.applySaveFailed(
                                it,
                                dispatch.getString(R.string.vm_009)
                            )
                        }
                        onDone(false, dispatch.getString(R.string.vm_010))
                        return@launch
                    }
                    dispatch.updateState {
                        PairingStateReducer.applyClaimSuccess(
                            it,
                            result.accountId.ifBlank { accountId },
                            result.deviceSecret,
                            relayUrl,
                            dispatch.getString(R.string.vm_011, result.desktopName.ifBlank { desktopName })
                        )
                    }
                    onDone(true, dispatch.getString(R.string.vm_012))
                    transports.logPair("desktop=${result.desktopName.ifBlank { desktopName }}")
                } else {
                    val err = result.error.ifBlank { dispatch.getString(R.string.vm_013) }
                    dispatch.updateState { PairingStateReducer.applyClaimFailed(it, err) }
                    onDone(false, err)
                }
            }
        }
    }

    /** v1.6 手动配对：账号/密钥/relay 地址直连。 */
    fun pairDesktop(accountId: String, secret: String, relayUrl: String) {
        val trimmedAccount = accountId.trim()
        val trimmedSecret = secret.trim()
        val trimmedRelay = relayUrl.trim()

        when (PairingStateReducer.validateManualInput(accountId, secret)) {
            ManualInputError.BLANK_ACCOUNT -> {
                dispatch.updateState {
                    it.copy(appError = AppError("INPUT_EMPTY", dispatch.getString(R.string.vm_014)))
                }
                return
            }
            ManualInputError.BLANK_SECRET -> {
                dispatch.updateState {
                    it.copy(appError = AppError("INPUT_EMPTY", dispatch.getString(R.string.vm_015)))
                }
                return
            }
            ManualInputError.NONE -> {}
        }

        // v4.3 M-2: 手动配对填的是房间主 secret，可做 E2EE 公钥 HMAC 绑定校验
        // 临界区：凭据保存 + 状态更新原子完成（与 claimPairingByQr/pairCloud 互斥）
        synchronized(pairingLock) {
            prefs.secretIsMaster = true
            // P0-3: 加密存储不可用时拒绝保存敏感凭据
            if (!prefs.savePairingInfo(trimmedAccount, trimmedSecret, trimmedRelay)) {
                dispatch.updateState {
                    it.copy(appError = AppError(
                        "SECURE_STORAGE_UNAVAILABLE",
                        dispatch.getString(R.string.vm_009)
                    ))
                }
                return
            }

            dispatch.updateState {
                PairingStateReducer.applyManualSuccess(
                    it,
                    trimmedAccount,
                    trimmedSecret,
                    trimmedRelay,
                    dispatch.getString(R.string.vm_016)
                )
            }
        }

        // v3.0: 走 Transport（connect/send/close 统一入口）
        transports.connectRelay(
            TransportParams(
                relayUrl = trimmedRelay,
                accountId = trimmedAccount,
                secret = trimmedSecret
            )
        )
    }

    /** v2.x 云端直连配对：探活 → 存配置 → 拉会话。 */
    fun pairCloud(cloudUrl: String, apiKey: String, workspacePath: String) {
        val trimmedUrl = cloudUrl.trim()
        val trimmedKey = apiKey.trim()
        val trimmedWorkspace = workspacePath.trim()

        if (trimmedUrl.isBlank()) {
            dispatch.updateState {
                it.copy(appError = AppError("INPUT_EMPTY", dispatch.getString(R.string.vm_017)))
            }
            return
        }

        dispatch.updateState {
            CloudPairReducer.applyHealthCheckStarted(
                it,
                dispatch.getString(R.string.vm_018)
            )
        }

        transports.checkCloudHealth(trimmedUrl, trimmedKey) { isSuccess, message ->
            if (isSuccess) {
                // 临界区：凭据保存 + 状态更新原子完成（与 pairDesktop/claimPairingByQr 互斥）；
                // 拉会话是网络 I/O，在锁外执行
                val proceed = synchronized(pairingLock) {
                    // P0-3: 加密存储不可用时拒绝保存敏感凭据
                    if (!prefs.saveCloudConfig(trimmedUrl, trimmedKey, trimmedWorkspace)) {
                        dispatch.updateState {
                            CloudPairReducer.applySaveFailed(
                                it,
                                dispatch.getString(R.string.vm_019)
                            )
                        }
                        false
                    } else {
                        dispatch.updateState {
                            CloudPairReducer.applySuccess(it, trimmedUrl, trimmedKey, trimmedWorkspace)
                        }
                        true
                    }
                }
                if (proceed) {
                    // 拉取云端真实会话列表
                    transports.getCloudSessions(trimmedUrl, trimmedKey) { realSessions ->
                        dispatch.updateState { state ->
                            CloudPairReducer.applySessionsLoaded(state, realSessions)
                        }
                    }
                }
            } else {
                dispatch.updateState { CloudPairReducer.applyHealthFailed(it, message) }
            }
        }
    }

    /** 解绑：断开所有连接并复位配对相关状态。 */
    fun unpair() {
        transports.disconnectRelay()
        transports.cancelCloudStream()
        transports.stopTaskProgress()
        synchronized(pairingLock) {
            dispatch.updateState {
                it.copy(
                    isPaired = false,
                    isRelayConnected = false,
                    isAuthenticated = false,
                    isDesktopOnline = false,
                    isGenerating = false,
                    isReconnecting = false,
                    statusBanner = null
                )
            }
        }
    }

    /** v4.3 M-5: 按当前目标（targetDesktopId 或主 desktop）刷新 E2EE 就绪状态。 */
    fun refreshE2eePeerReady() {
        // synchronized 可重入：在 claim 临界区内调用时是同一线程，不会死锁
        synchronized(pairingLock) {
            val s = dispatch.currentState
            // v5.0.3 (A-1): desktop_list 为空时（多桌面路由关闭）回退到最近协商过的对端，
            // 否则明明能加密，UI 却显示未就绪
            val target = DeviceListReducer.selectE2eeTarget(s.targetDesktopId, s.desktopList)
                .ifEmpty { e2ee.lastNegotiatedPeerId() ?: "" }
            val ready = target.isNotEmpty() && e2ee.hasPeerKey(target)
            if (s.e2eePeerReady != ready) {
                dispatch.updateState { it.copy(e2eePeerReady = ready) }
            }
        }
    }
}
