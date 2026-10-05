package com.opencode.android.coordinator

import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem
import com.opencode.android.network.DesktopInfo
import com.opencode.android.network.PairClaimResult
import com.opencode.android.network.TransportParams
import kotlinx.coroutines.runBlocking

/** 阶段 2: StateDispatcher 的测试替身。launch 捕获协程块，由测试确定性执行。 */
class FakeStateDispatcher(
    initial: OpenCodeUiState = OpenCodeUiState(),
    private val stringFn: (Int, Array<out Any>) -> String = { id, args ->
        "s$id" + args.joinToString(prefix = "(", postfix = ")")
    },
) : StateDispatcher {
    private val lock = Any()
    private var _state: OpenCodeUiState = initial

    /** 每次 updateState 后的完整状态快照（含中间态，可断言时序）。 */
    val updates = mutableListOf<OpenCodeUiState>()

    /** 被捕获尚未执行的协程块。 */
    val launched = mutableListOf<suspend () -> Unit>()

    override val currentState: OpenCodeUiState
        get() = synchronized(lock) { _state }

    override fun updateState(transform: (OpenCodeUiState) -> OpenCodeUiState) {
        synchronized(lock) {
            _state = transform(_state)
            updates.add(_state)
        }
    }

    override fun launch(block: suspend () -> Unit) {
        synchronized(lock) { launched.add(block) }
    }

    override fun getString(resId: Int, vararg args: Any): String = stringFn(resId, args)

    /** 同步执行所有已捕获的协程块（确定性时序，替代 viewModelScope）。 */
    fun runLaunched() = runBlocking {
        val blocks = synchronized(lock) { launched.toList().also { launched.clear() } }
        blocks.forEach { it() }
    }
}

/** 阶段 2: PairingPrefs 的测试替身（内存实现）。 */
class FakePairingPrefs(
    var pairingInfoResult: Boolean = true,
    var cloudConfigResult: Boolean = true,
) : PairingPrefs, TaskStatusPrefs {
    override var secretIsMaster: Boolean = false
    var savedPairing: Triple<String, String, String>? = null
    var savedCloud: Triple<String, String, String>? = null
    var savedTaskStatus: Triple<String, String, String>? = null
    var taskStatusToRestore: Triple<String, String, String> = Triple("IDLE", "", "")

    override fun savePairingInfo(accountId: String, secret: String, relayUrl: String): Boolean {
        if (pairingInfoResult) savedPairing = Triple(accountId, secret, relayUrl)
        return pairingInfoResult
    }

    override fun saveCloudConfig(cloudUrl: String, apiKey: String, workspacePath: String): Boolean {
        if (cloudConfigResult) savedCloud = Triple(cloudUrl, apiKey, workspacePath)
        return cloudConfigResult
    }

    override fun saveTaskStatus(status: String, detail: String, sessionId: String) {
        savedTaskStatus = Triple(status, detail, sessionId)
    }

    override fun getTaskStatus(): Triple<String, String, String> = taskStatusToRestore
}

/** 阶段 2: PairingE2ee 的测试替身。 */
class FakePairingE2ee(
    var pubkeyB64: String? = "fake-pubkey",
    var storePeerResult: Boolean = true,
    private val peerKeys: MutableSet<String> = mutableSetOf(),
) : PairingE2ee {
    val storedPeers = mutableListOf<Triple<String, String, String>>()

    override fun ownPublicKeyB64(): String? = pubkeyB64

    override fun storePeerPubkey(deviceId: String, pubkeyB64: String, sig: String): Boolean {
        if (storePeerResult) {
            storedPeers.add(Triple(deviceId, pubkeyB64, sig))
            peerKeys.add(deviceId)
        }
        return storePeerResult
    }

    override fun hasPeerKey(deviceId: String): Boolean = peerKeys.contains(deviceId)
}

/** 阶段 2: PairingTransports 的测试替身。 */
class FakePairingTransports : PairingTransports {
    /** claimPairing 的行为：成功结果 / 或抛异常（网络失败）。 */
    var claimResult: PairClaimResult = PairClaimResult(success = true, deviceSecret = "dev-secret")
    var claimError: Exception? = null
    var claimedE2eePubkey: String? = null

    var connectedParams: TransportParams? = null
    var disconnectCalls = 0
    var cancelStreamCalls = 0
    var stopProgressCalls = 0
    val pairLogs = mutableListOf<String>()

    var healthResult: Pair<Boolean, String> = true to ""
    var sessionsResult: List<SessionItem> = emptyList()

    override suspend fun claimPairing(
        relayUrl: String,
        accountId: String,
        pairingToken: String,
        e2eePubkey: String
    ): PairClaimResult {
        claimedE2eePubkey = e2eePubkey
        claimError?.let { throw it }
        return claimResult
    }

    override fun connectRelay(params: TransportParams) {
        connectedParams = params
    }

    override fun disconnectRelay() { disconnectCalls++ }

    override fun checkCloudHealth(
        cloudUrl: String,
        apiKey: String,
        callback: (Boolean, String) -> Unit
    ) = callback(healthResult.first, healthResult.second)

    override fun getCloudSessions(
        cloudUrl: String,
        apiKey: String,
        callback: (List<SessionItem>) -> Unit
    ) = callback(sessionsResult)

    override fun cancelCloudStream() { cancelStreamCalls++ }

    override fun stopTaskProgress() { stopProgressCalls++ }

    override fun logPair(detail: String) { pairLogs.add(detail) }
}

/** 快捷构造：带一台主 desktop 的状态。 */
fun stateWithPrimaryDesktop(): OpenCodeUiState = OpenCodeUiState(
    desktopList = listOf(
        DesktopInfo(deviceId = "d1", deviceName = "n1", isPrimary = true)
    )
)
