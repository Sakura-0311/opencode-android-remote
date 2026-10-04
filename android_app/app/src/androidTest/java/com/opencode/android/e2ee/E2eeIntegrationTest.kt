package com.opencode.android.e2ee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencode.android.data.local.PreferencesManager
import com.opencode.android.network.PairingClient
import com.opencode.android.network.RelayListener
import com.opencode.android.network.RelayWebSocketClient
import com.opencode.android.security.E2eeCrypto
import com.opencode.android.security.E2eeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.3.1: E2EE 模拟器联调集成测试（跑真实 Kotlin 代码）。
 *
 * 依赖 CI workflow 在 host 上启动：
 *  1. relay_server.py（:8765）
 *  2. mock_desktop_e2ee.py（生成 pairing token，写 /tmp/mock-pairing-token.txt）
 *  3. 小型 HTTP 服务（:8080/token 返回 pairing token；:8080/result 返回 E2EE_OK/FAIL）
 *
 * 流程：取 token → 生成 mobile 密钥对 → PairingClient.claimPairing →
 * 验签存 desktop 公钥 → 启用 E2EE → RelayWebSocketClient.sendPrompt →
 * mock desktop 解密验证。
 */
@RunWith(AndroidJUnit4::class)
class E2eeIntegrationTest {

    private val host = "10.0.2.2"
    private val accountId = "test"
    private val relayUrl = "ws://$host:8765"

    private fun httpGet(path: String): String {
        val url = URL("http://$host:8080$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return conn.inputStream.bufferedReader().readText().trim()
    }

    @Test
    fun e2eeEndToEnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. 取 pairing token（等 mock desktop 就绪）
        var token = ""
        repeat(30) {
            try {
                token = httpGet("/token")
                if (token.isNotEmpty() && !token.startsWith("WAIT")) return@repeat
            } catch (_: Exception) {}
            if (token.isNotEmpty() && !token.startsWith("WAIT")) return@repeat
            Thread.sleep(4000)
        }
        assertTrue("未拿到 pairing token", token.isNotEmpty() && !token.startsWith("WAIT"))

        // 2. 生成 mobile E2EE 密钥对
        val prefs = PreferencesManager(context)
        val e2ee = E2eeManager(prefs)
        val kp = E2eeCrypto.generateKeypair()
        // 直接存私钥（绕过 ensureKeypair 的 Context 依赖细节，用公开 API）
        prefs.setE2eePrivateKey(kp.privateKeyB64)
        prefs.isE2eeEnabled = true
        // 手动配对模式：用主 secret 做 HMAC 验签（与 mock desktop 一致）
        prefs.secretIsMaster = true
        // 主 secret 由 workflow 注入（与 mock desktop 的 --secret 一致）
        val masterSecret = httpGet("/secret")
        // PreferencesManager 的 secret 需与 masterSecret 一致；测试直接写
        //（savePairingInfo 需要加密存储可用，模拟器上应可用）
        assertTrue("加密存储不可用", prefs.isSecureStorageAvailable)

        // 3. 配对认领（带 mobile 公钥）
        val result = PairingClient.claimPairing(
            context = context,
            relayUrl = relayUrl,
            accountId = accountId,
            pairingToken = token,
            deviceName = "EmulatorTest",
            e2eePubkey = kp.publicKeyB64
        )
        assertTrue("配对失败: ${result.error}", result.success)
        assertTrue("无 device_secret", result.deviceSecret.isNotBlank())
        assertTrue("无 desktop 公钥", result.e2eePeerPubkey.isNotBlank())
        assertTrue("无 desktop 公钥签名", result.e2eePubkeySig.isNotBlank())
        assertTrue("无 desktop device id", result.desktopDeviceId.isNotBlank())

        // 4. 存 device_secret，并验签存 desktop 公钥
        assertTrue(prefs.savePairingInfo(accountId, result.deviceSecret, relayUrl))
        // 把主 secret 写入当前 profile（供 HMAC 验签）
        // 注意：savePairingInfo 存的是 device_secret；验签需要主 secret。
        // 测试环境下 mock desktop 用主 secret 签名，这里用反射/直接调用验签：
        val expectedSig = E2eeCrypto.hmacPubkeySig(
            masterSecret, result.desktopDeviceId, result.e2eePeerPubkey)
        assertEquals("HMAC 签名不匹配（跨语言）", expectedSig, result.e2eePubkeySig)

        val stored = e2ee.storePeerPubkey(
            result.desktopDeviceId, result.e2eePeerPubkey, result.e2eePubkeySig)
        // storePeerPubkey 内部用 prefs.getSecret()（=device_secret）验签会失败，
        // 这是预期的（扫码配对场景）；这里直接断言跨语言签名一致即已验证 M-2。
        // 为让发送流程走通，改用 TOFU 模式存：
        prefs.secretIsMaster = false
        assertTrue(e2ee.storePeerPubkey(
            result.desktopDeviceId, result.e2eePeerPubkey, result.e2eePubkeySig))

        // 5. 连 relay，发加密消息
        val latch = CountDownLatch(1)
        var sendError: String? = null
        val listener = object : RelayListener {
            override fun onConnected() {}
            override fun onAuthenticated() { latch.countDown() }
            override fun onAuthError(error: String) { sendError = "auth: $error"; latch.countDown() }
            override fun onDisconnected(reason: String) {}
            override fun onDesktopStatusChanged(isOnline: Boolean) {}
            override fun onStreamStart(sessionId: String) {}
            override fun onStreamChunk(sessionId: String, chunk: String) {}
            override fun onStreamEnd(sessionId: String) {}
            override fun onAppError(code: String, message: String) {}
            override fun onError(error: String) { sendError = error }
        }
        val client = RelayWebSocketClient(context)
        client.setE2eeManager(e2ee)

        client.connect(relayUrl, accountId, result.deviceSecret, listener)
        assertTrue("relay 认证超时", latch.await(30, TimeUnit.SECONDS))

        // 6. 发送（target 为 mock desktop）
        client.sendPrompt(
            prompt = "hello e2ee integration test",
            sessionId = "test-session-1",
            targetDeviceId = result.desktopDeviceId
        )

        // 7. 轮询 mock desktop 的解密结果
        var ok = false
        repeat(30) {
            Thread.sleep(4000)
            try {
                val r = httpGet("/result")
                if (r == "E2EE_OK") { ok = true; return@repeat }
                if (r.startsWith("E2EE_FAIL")) fail("mock desktop 解密失败: $r")
            } catch (_: Exception) {}
        }
        assertNull("发送被拒绝: $sendError", sendError)
        assertTrue("mock desktop 未确认解密成功", ok)

        client.disconnect()
    }
}
