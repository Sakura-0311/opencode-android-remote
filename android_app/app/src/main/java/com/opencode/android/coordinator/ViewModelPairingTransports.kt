package com.opencode.android.coordinator

import android.app.Application
import com.opencode.android.data.model.SessionItem
import com.opencode.android.network.CloudApiClient
import com.opencode.android.network.PairClaimResult
import com.opencode.android.network.PairingClient
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.network.Transport
import com.opencode.android.network.TransportListener
import com.opencode.android.network.TransportParams
import com.opencode.android.service.OpenCodeKeepAliveService
import com.opencode.android.util.OpLog

/**
 * 阶段 2: PairingTransports 的生产实现。
 * 把需要 Context 的调用（PairingClient / OpLog / KeepAliveService）消化在这里，
 * Coordinator 本体保持纯粹、可单测。
 */
class ViewModelPairingTransports(
    private val app: Application,
    private val relayTransport: Transport,
    private val transportListener: TransportListener,
    private val relayClient: RelayWebSocketClient,
    private val cloudClient: CloudApiClient,
) : PairingTransports {

    override suspend fun claimPairing(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        e2eePubkey: String
    ): PairClaimResult =
        PairingClient.claimPairing(app, relayUrl, accountId, pairingToken, e2eePubkey = e2eePubkey)

    override fun connectRelay(params: TransportParams) =
        relayTransport.connect(params, transportListener)

    override fun disconnectRelay() = relayClient.disconnect()

    override fun checkCloudHealth(
        cloudUrl: String,
        apiKey: String,
        callback: (Boolean, String) -> Unit
    ) = cloudClient.checkHealth(cloudUrl, apiKey, callback)

    override fun getCloudSessions(
        cloudUrl: String,
        apiKey: String,
        callback: (List<SessionItem>) -> Unit
    ) = cloudClient.getSessions(cloudUrl, apiKey, callback)

    override fun cancelCloudStream() = cloudClient.cancelCurrentStream()

    override fun stopTaskProgress() = OpenCodeKeepAliveService.stopTaskProgress(app)

    override fun logPair(detail: String) = OpLog.record(app, OpLog.OpType.PAIR, detail)
}
