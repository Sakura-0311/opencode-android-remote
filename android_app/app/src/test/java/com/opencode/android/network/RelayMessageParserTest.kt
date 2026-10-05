package com.opencode.android.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * RelayMessageParser 单测（纯 JVM）：覆盖解析/解密拆分后的各种 Outcome，
 * 锁定与拆分前一致的行为。
 */
class RelayMessageParserTest {

    private val noDecrypt = RelayMessageParser()

    @Test fun plainMessage_ok() {
        val outcome = noDecrypt.parseAndDecrypt("""{"type":"ping","timestamp":123}""")
        assertTrue(outcome is RelayMessageParser.Outcome.Ok)
        val ok = outcome as RelayMessageParser.Outcome.Ok
        assertEquals("ping", ok.json.optString("type"))
        assertNull(ok.decryptedFrom)
    }

    @Test fun badJson_badJsonOutcome() {
        val outcome = noDecrypt.parseAndDecrypt("{not json")
        assertTrue(outcome is RelayMessageParser.Outcome.BadJson)
    }

    @Test fun emptyString_badJsonOutcome() {
        val outcome = noDecrypt.parseAndDecrypt("")
        assertTrue(outcome is RelayMessageParser.Outcome.BadJson)
    }

    @Test fun e2eeDecryptOk_mergesInnerFields() {
        val parser = RelayMessageParser(decrypt = { _, _, _ ->
            """{"type":"stream_chunk","chunk":"hello"}"""
        })
        val outcome = parser.parseAndDecrypt(
            """{"e2ee":true,"encrypted_payload":"abc","source_device_id":"desk1","session_id":"s1"}"""
        )
        assertTrue(outcome is RelayMessageParser.Outcome.Ok)
        val ok = outcome as RelayMessageParser.Outcome.Ok
        assertEquals("stream_chunk", ok.json.optString("type"))
        assertEquals("hello", ok.json.optString("chunk"))
        assertEquals("desk1", ok.decryptedFrom)
        // 路由字段保持明文
        assertEquals("desk1", ok.json.optString("source_device_id"))
    }

    @Test fun e2eeDecryptNull_decryptFailed() {
        val parser = RelayMessageParser(decrypt = { _, _, _ -> null })
        val outcome = parser.parseAndDecrypt(
            """{"e2ee":true,"encrypted_payload":"abc"}"""
        )
        assertTrue(outcome is RelayMessageParser.Outcome.DecryptFailed)
    }

    @Test fun e2eeNoDecryptor_decryptFailed() {
        // 未注入 decrypt（等价于 e2eeManager 为空）→ 解密失败
        val outcome = noDecrypt.parseAndDecrypt(
            """{"e2ee":true,"encrypted_payload":"abc"}"""
        )
        assertTrue(outcome is RelayMessageParser.Outcome.DecryptFailed)
    }

    @Test fun e2eeBadInnerJson_badInnerJson() {
        val parser = RelayMessageParser(decrypt = { _, _, _ -> "not-json{" })
        val outcome = parser.parseAndDecrypt(
            """{"e2ee":true,"encrypted_payload":"abc"}"""
        )
        assertTrue(outcome is RelayMessageParser.Outcome.BadInnerJson)
    }

    @Test fun e2eeFlagFalse_noDecryptAttempted() {
        var called = false
        val parser = RelayMessageParser(decrypt = { _, _, _ -> called = true; null })
        val outcome = parser.parseAndDecrypt("""{"type":"pong"}""")
        assertTrue(outcome is RelayMessageParser.Outcome.Ok)
        assertFalse(called)
    }

    @Test fun decryptReceivesRoutingFields() {
        var gotPayload = ""
        var gotSrc = ""
        var gotSess = ""
        val parser = RelayMessageParser(decrypt = { payload, srcId, sessId ->
            gotPayload = payload; gotSrc = srcId; gotSess = sessId
            """{"type":"x"}"""
        })
        parser.parseAndDecrypt(
            """{"e2ee":true,"encrypted_payload":"PAY","source_device_id":"D1","session_id":"S9"}"""
        )
        assertEquals("PAY", gotPayload)
        assertEquals("D1", gotSrc)
        assertEquals("S9", gotSess)
    }
}
