package com.opencode.android.coordinator

import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem
import com.opencode.android.network.PairClaimResult
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 阶段 2: PairingCoordinator 单测（纯 JVM）。
 * 覆盖 docx §9 点名的四个场景：并发 pairDesktop、E2EE 中途失败、
 * 凭据覆盖写、relay 认证失败后重试。
 */
class PairingCoordinatorTest {

    private fun setup(
        state: OpenCodeUiState = OpenCodeUiState(),
        prefs: FakePairingPrefs = FakePairingPrefs(),
        e2ee: FakePairingE2ee = FakePairingE2ee(),
        transports: FakePairingTransports = FakePairingTransports(),
        dispatch: FakeStateDispatcher = FakeStateDispatcher(state),
    ) = PairingCoordinator(prefs, e2ee, transports, dispatch) to
            TestDeps(prefs, e2ee, transports, dispatch)

    private data class TestDeps(
        val prefs: FakePairingPrefs,
        val e2ee: FakePairingE2ee,
        val transports: FakePairingTransports,
        val dispatch: FakeStateDispatcher,
    )

    // ============ claimPairingByQr ============

    @Test fun claimQr_success_fullFlow() {
        val (c, d) = setup()
        d.transports.claimResult = PairClaimResult(
            success = true, deviceSecret = "dev-secret", accountId = "acc",
            desktopName = "desk", desktopDeviceId = "d1",
            e2eePeerPubkey = "peer-pub", e2eePubkeySig = "sig"
        )
        var done: Pair<Boolean, String>? = null
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, msg -> done = ok to msg }
        assertEquals(1, d.dispatch.launched.size) // 协程被捕获，未自动执行
        d.dispatch.runLaunched()

        assertNotNull(done)
        assertTrue(done!!.first)
        val s = d.dispatch.currentState
        assertEquals(AppMode.DESKTOP_RELAY, s.appMode)
        assertTrue(s.isPaired)
        assertEquals("dev-secret", s.secret)
        assertEquals("http://r", s.relayUrl)
        assertFalse(d.prefs.secretIsMaster) // 扫码存的是 device_secret
        assertEquals(Triple("acc", "dev-secret", "http://r"), d.prefs.savedPairing)
        assertEquals("fake-pubkey", d.transports.claimedE2eePubkey)
        assertEquals(1, d.transports.pairLogs.size)
        assertTrue(s.showE2eeChannelDialog) // E2EE 交换成功给一次提示
    }

    @Test fun claimQr_networkException_mapsToPairFailed() {
        val (c, d) = setup()
        d.transports.claimError = RuntimeException("boom")
        var done: Pair<Boolean, String>? = null
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, msg -> done = ok to msg }
        d.dispatch.runLaunched()

        assertFalse(done!!.first)
        assertEquals("PAIR_FAILED", d.dispatch.currentState.appError?.code)
        assertEquals("boom", d.dispatch.currentState.appError?.message)
        assertNull(d.prefs.savedPairing) // 失败不写凭据
    }

    @Test fun claimQr_e2eeMidFailure_setsUntrustedButPairingContinues() {
        // docx §9 场景：E2EE 公钥交换中途失败——原行为是置错但继续配对（不断言修 bug，只锁定行为）
        val (c, d) = setup(e2ee = FakePairingE2ee(storePeerResult = false))
        d.transports.claimResult = PairClaimResult(
            success = true, deviceSecret = "dev-secret",
            desktopDeviceId = "d1", e2eePeerPubkey = "peer-pub", e2eePubkeySig = "bad-sig"
        )
        var doneOk = false
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, _ -> doneOk = ok }
        d.dispatch.runLaunched()

        // 中间态出现过 E2EE_PUBKEY_UNTRUSTED
        assertTrue(d.dispatch.updates.any { it.appError?.code == "E2EE_PUBKEY_UNTRUSTED" })
        // 但配对流程继续走完（与原逻辑一致）
        assertTrue(doneOk)
        assertTrue(d.dispatch.currentState.isPaired)
    }

    @Test fun claimQr_saveFails_secureStorageUnavailable() {
        val (c, d) = setup(prefs = FakePairingPrefs(pairingInfoResult = false))
        var done: Pair<Boolean, String>? = null
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, msg -> done = ok to msg }
        d.dispatch.runLaunched()

        assertFalse(done!!.first)
        assertEquals("SECURE_STORAGE_UNAVAILABLE", d.dispatch.currentState.appError?.code)
        assertFalse(d.dispatch.currentState.isPaired)
    }

    @Test fun claimQr_authFailureThenRetry() {
        // docx §9 场景：relay 认证失败后重试——第一次失败，第二次成功
        val (c, d) = setup()
        d.transports.claimResult = PairClaimResult(success = false, error = "auth denied")
        val results = mutableListOf<Boolean>()
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, _ -> results.add(ok) }
        d.dispatch.runLaunched()
        assertEquals(listOf(false), results)
        assertEquals("PAIR_FAILED", d.dispatch.currentState.appError?.code)

        d.transports.claimResult = PairClaimResult(success = true, deviceSecret = "s2")
        c.claimPairingByQr("http://r", "acc", "tok", "desk") { ok, _ -> results.add(ok) }
        d.dispatch.runLaunched()
        assertEquals(listOf(false, true), results)
        assertTrue(d.dispatch.currentState.isPaired)
        assertNull(d.dispatch.currentState.appError)
    }

    // ============ pairDesktop ============

    @Test fun pairDesktop_blankAccount_rejected() {
        val (c, d) = setup()
        c.pairDesktop("  ", "secret", "http://r")
        assertEquals("INPUT_EMPTY", d.dispatch.currentState.appError?.code)
        assertNull(d.transports.connectedParams)
        assertNull(d.prefs.savedPairing)
    }

    @Test fun pairDesktop_blankSecret_rejected() {
        val (c, d) = setup()
        c.pairDesktop("acc", "   ", "http://r")
        assertEquals("INPUT_EMPTY", d.dispatch.currentState.appError?.code)
        assertNull(d.transports.connectedParams)
    }

    @Test fun pairDesktop_saveFails_noConnect() {
        val (c, d) = setup(prefs = FakePairingPrefs(pairingInfoResult = false))
        c.pairDesktop("acc", "secret", "http://r")
        assertEquals("SECURE_STORAGE_UNAVAILABLE", d.dispatch.currentState.appError?.code)
        assertNull(d.transports.connectedParams) // 加密不可用时不连接
    }

    @Test fun pairDesktop_success_trimsAndConnects() {
        val (c, d) = setup()
        c.pairDesktop("  acc ", " secret ", " http://r ")
        val s = d.dispatch.currentState
        assertEquals(AppMode.DESKTOP_RELAY, s.appMode)
        assertTrue(s.isPaired)
        assertTrue(d.prefs.secretIsMaster) // 手动配对=主 secret
        assertEquals(Triple("acc", "secret", "http://r"), d.prefs.savedPairing)
        val params = d.transports.connectedParams!!
        assertEquals("http://r", params.relayUrl)
        assertEquals("acc", params.accountId)
        assertEquals("secret", params.secret)
    }

    @Test fun pairDesktop_overwriteWhenCredentialsExist() {
        // docx §9 场景：凭据已存在时的覆盖写——第二次覆盖第一次
        val (c, d) = setup()
        c.pairDesktop("acc1", "s1", "http://r1")
        c.pairDesktop("acc2", "s2", "http://r2")
        assertEquals(Triple("acc2", "s2", "http://r2"), d.prefs.savedPairing)
        assertEquals("acc2", d.dispatch.currentState.accountId)
        assertEquals("http://r2", d.transports.connectedParams!!.relayUrl)
    }

    @Test fun pairDesktop_concurrent_noCrashAndConsistent() {
        // docx §9 场景：并发两次 pairDesktop——不崩溃，终态一致（其一胜出）
        val (c, d) = setup()
        val latch = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        val t1 = Thread {
            latch.await(5, TimeUnit.SECONDS)
            try { c.pairDesktop("a1", "s1", "http://r1") } catch (e: Throwable) { errors.add(e) }
        }
        val t2 = Thread {
            latch.await(5, TimeUnit.SECONDS)
            try { c.pairDesktop("a2", "s2", "http://r2") } catch (e: Throwable) { errors.add(e) }
        }
        t1.start(); t2.start()
        latch.countDown()
        t1.join(5000); t2.join(5000)

        assertTrue("并发抛异常: $errors", errors.isEmpty())
        val s = d.dispatch.currentState
        assertTrue(s.isPaired)
        // 终态一致：state 与 prefs 是同一胜出者
        val winner = d.prefs.savedPairing!!
        assertEquals(winner.first, s.accountId)
    }

    // ============ pairCloud ============

    @Test fun pairCloud_blankUrl_rejected() {
        val (c, d) = setup()
        c.pairCloud("   ", "k", "/w")
        assertEquals("INPUT_EMPTY", d.dispatch.currentState.appError?.code)
    }

    @Test fun pairCloud_healthFail() {
        val (c, d) = setup()
        d.transports.healthResult = false to "timeout"
        c.pairCloud("https://h", "k", "/w")
        val s = d.dispatch.currentState
        assertFalse(s.isPaired)
        assertEquals("CLOUD_CHECK_FAILED", s.appError?.code)
        assertEquals("timeout", s.appError?.message)
    }

    @Test fun pairCloud_saveFails() {
        val (c, d) = setup(prefs = FakePairingPrefs(cloudConfigResult = false))
        c.pairCloud("https://h", "k", "/w")
        assertEquals("SECURE_STORAGE_UNAVAILABLE", d.dispatch.currentState.appError?.code)
        assertTrue(d.transports.sessionsResult.isEmpty()) // 存失败不拉会话
    }

    @Test fun pairCloud_success_loadsSessions() {
        val (c, d) = setup()
        d.transports.sessionsResult = listOf(
            SessionItem(id = "b", title = "tb", tag = "t", updatedAt = 1000L),
            SessionItem(id = "a", title = "ta", tag = "t", updatedAt = 2000L),
        )
        c.pairCloud("https://h", "k", "/w")
        val s = d.dispatch.currentState
        assertEquals(AppMode.CLOUD_HOSTED, s.appMode)
        assertTrue(s.isPaired)
        assertTrue(s.isAuthenticated)
        assertEquals(Triple("https://h", "k", "/w"), d.prefs.savedCloud)
        assertEquals(listOf("a", "b"), s.availableSessions.map { it.id })
    }

    // ============ unpair / refreshE2eePeerReady ============

    @Test fun unpair_resetsPairingState() {
        val (c, d) = setup(state = OpenCodeUiState(isPaired = true, isRelayConnected = true))
        c.unpair()
        assertEquals(1, d.transports.disconnectCalls)
        assertEquals(1, d.transports.cancelStreamCalls)
        assertEquals(1, d.transports.stopProgressCalls)
        val s = d.dispatch.currentState
        assertFalse(s.isPaired)
        assertFalse(s.isRelayConnected)
        assertFalse(s.isAuthenticated)
        assertFalse(s.isGenerating)
    }

    @Test fun refreshE2eePeerReady_withKey_marksReady() {
        val e2ee = FakePairingE2ee()
        e2ee.storePeerPubkey("d1", "pub", "sig")
        val (c, d) = setup(state = stateWithPrimaryDesktop(), e2ee = e2ee)
        c.refreshE2eePeerReady()
        assertTrue(d.dispatch.currentState.e2eePeerReady)
    }

    @Test fun refreshE2eePeerReady_noTarget_staysFalse() {
        val (c, d) = setup()
        c.refreshE2eePeerReady()
        assertFalse(d.dispatch.currentState.e2eePeerReady)
        assertTrue(d.dispatch.updates.isEmpty()) // 无变化时不写状态
    }
}
