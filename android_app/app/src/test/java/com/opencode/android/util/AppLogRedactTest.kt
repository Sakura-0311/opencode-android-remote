package com.opencode.android.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v2.3: AppLog 脱敏单测。
 */
class AppLogRedactTest {

    @Test
    fun `redact masks secret kv`() {
        val s = AppLog.redact("login failed secret=abc123xyz password: hunter2")
        assertFalse(s.contains("abc123xyz"))
        assertFalse(s.contains("hunter2"))
        assertTrue(s.contains("secret=***"))
    }

    @Test
    fun `redact masks authorization header`() {
        val s = AppLog.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9")
        assertFalse(s.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redact masks url query`() {
        val s = AppLog.redact("GET https://relay.example.com/ws?token=abc123&x=1 done")
        assertFalse(s.contains("token=abc123"))
        assertTrue(s.contains("https://relay.example.com/ws?***"))
    }

    @Test
    fun `redact leaves normal text alone`() {
        val s = AppLog.redact("ws closed code=1006 reason=timeout")
        assertEquals("ws closed code=1006 reason=timeout", s)
    }

    @Test
    fun `redactJson masks sensitive keys and drops bodies`() {
        val obj = JSONObject().apply {
            put("type", "auth")
            put("secret", "s3cr3t")
            put("prompt", "do something evil")
            put("chunk", "some code here")
            put("nested", JSONObject().apply { put("token", "t0k") })
        }
        val out = AppLog.redactJson(obj)
        assertEquals("auth", out.getString("type"))
        assertEquals("***", out.getString("secret"))
        assertTrue(out.getString("prompt").startsWith("<omitted"))
        assertTrue(out.getString("chunk").startsWith("<omitted"))
        assertEquals("***", out.getJSONObject("nested").getString("token"))
    }

    @Test
    fun `ring log writes and exports without secrets`() {
        val dir = Files.createTempDirectory("applog-test").toFile()
        AppLog.init(dir)
        AppLog.i("Relay", "connected secret=topsecret123")
        AppLog.e("Relay", "auth failed for user")
        val dest = File(dir.parentFile, "export.txt")
        val exported = AppLog.exportLogFile(dest)
        assertNotNull(exported)
        val text = dest.readText()
        assertFalse(text.contains("topsecret123"))
        assertTrue(text.contains("secret=***"))
        assertTrue(text.contains("auth failed for user"))
        dir.deleteRecursively()
        dest.delete()
    }
}
