package com.opencode.android.security

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * v4.6.0: E2EE 跨端互操作测试。与 Python（desktop_agent/modules/test_e2ee_v2.py）
 * 读取同一份 tests/e2ee/interop_vectors.json，验证：
 * 1. HKDF 派生的 k_m2d/k_d2m 与向量一致（跨语言密钥派生一致）
 * 2. 固定 nonce 的 m2d/d2m 向量可解密，内层 JSON 正确（跨语言加解密一致）
 * 3. AAD 篡改（sender）解密失败
 *
 * 向量文件放在 app/src/test/resources/e2ee/interop_vectors.json
 * （由 tests/e2ee/gen_interop_vectors.py 生成后复制）。
 */
class E2eeInteropTest {

    @Before
    fun setup() {
        E2eeCrypto.b64Encode = { b -> java.util.Base64.getEncoder().encodeToString(b) }
        E2eeCrypto.b64Decode = { s -> java.util.Base64.getDecoder().decode(s) }
    }

    private fun loadVectors(): JSONObject {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("e2ee/interop_vectors.json")
            ?: error("找不到测试资源 e2ee/interop_vectors.json")
        return JSONObject(stream.bufferedReader().readText())
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun `hkdf derived keys match vectors`() {
        val vec = loadVectors()
        // 手机视角：己方私钥 + desktop 公钥
        val mKeys = E2eeCrypto.deriveMessageKeys(
            vec.getString("mobile_priv_b64"), vec.getString("desktop_pub_b64"))
        assertArrayEquals(hexToBytes(vec.getString("k_m2d_hex")), mKeys.m2d)
        assertArrayEquals(hexToBytes(vec.getString("k_d2m_hex")), mKeys.d2m)
        // desktop 视角：己方私钥 + 手机公钥，应得同一对密钥
        val dKeys = E2eeCrypto.deriveMessageKeys(
            vec.getString("desktop_priv_b64"), vec.getString("mobile_pub_b64"))
        assertArrayEquals(mKeys.m2d, dKeys.m2d)
        assertArrayEquals(mKeys.d2m, dKeys.d2m)
    }

    @Test
    fun `fixed vectors decrypt with correct aad`() {
        val vec = loadVectors()
        val mKeys = E2eeCrypto.deriveMessageKeys(
            vec.getString("mobile_priv_b64"), vec.getString("desktop_pub_b64"))
        val arr = vec.getJSONArray("vectors")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val dir = v.getString("direction")
            val key = if (dir == "m2d") mKeys.m2d else mKeys.d2m
            val pt = E2eeCrypto.decrypt(
                v.getString("encrypted_payload_b64"), key,
                v.getString("sender_device_id"), v.getString("session_id"))
            val inner = JSONObject(pt)
            val expected = v.getJSONObject("inner_json")
            // 逐字段比对（JSONObject.toString 顺序不可靠）
            assertEquals(expected.length(), inner.length())
            for (k in expected.keys()) {
                assertEquals("字段 $k", expected.get(k).toString(), inner.get(k).toString())
            }
        }
    }

    @Test
    fun `tampered aad fails to decrypt`() {
        val vec = loadVectors()
        val mKeys = E2eeCrypto.deriveMessageKeys(
            vec.getString("mobile_priv_b64"), vec.getString("desktop_pub_b64"))
        val v = vec.getJSONArray("vectors").getJSONObject(0)
        val key = if (v.getString("direction") == "m2d") mKeys.m2d else mKeys.d2m
        try {
            E2eeCrypto.decrypt(
                v.getString("encrypted_payload_b64"), key,
                "wrong-sender", v.getString("session_id"))
            fail("AAD 篡改应解密失败")
        } catch (e: Exception) {
            // 预期：GeneralSecurityException
        }
    }
}
