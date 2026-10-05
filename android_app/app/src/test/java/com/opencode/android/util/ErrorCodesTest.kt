package com.opencode.android.util

import com.opencode.android.R
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * v4.3.2 A3: ErrorCodes 单测（纯 JVM）。
 * 覆盖迭代方案 A3 路径 3：错误码 → 本地化文案的映射完整性。
 *
 * 核心断言：main 源码里所有 AppError("CODE") 字面量，必须在映射表里有条目；
 * 新增错误码忘记加文案时，这个测试会先失败，而不是让用户看到通用回退文案。
 */
class ErrorCodesTest {

    @Test
    fun `known codes resolve to specific entries`() {
        assertEquals(R.string.err_001, ErrorCodes.lookup("AUTH_FAILED").titleRes)
        assertEquals(R.string.err_013, ErrorCodes.lookup("DESKTOP_OFFLINE").titleRes)
        // v4.3.2 补齐的三个码
        assertEquals(R.string.err_061, ErrorCodes.lookup("PAIR_FAILED").titleRes)
        assertEquals(R.string.err_064, ErrorCodes.lookup("INPUT_EMPTY").titleRes)
        assertEquals(R.string.err_067, ErrorCodes.lookup("CLOUD_CHECK_FAILED").titleRes)
        assertEquals(R.string.err_070, ErrorCodes.lookup("E2EE_PUBKEY_UNTRUSTED").titleRes)
        assertEquals(R.string.err_073, ErrorCodes.lookup("CLOUD_UNREACHABLE").titleRes)
        assertEquals(R.string.err_076, ErrorCodes.lookup("RELAY_ERROR").titleRes)
        assertEquals(R.string.err_079, ErrorCodes.lookup("SEND_FAILED").titleRes)
        assertEquals(R.string.err_082, ErrorCodes.lookup("UNKNOWN_ERROR").titleRes)
    }

    @Test
    fun `unknown code falls back to generic entry`() {
        val info = ErrorCodes.lookup("SOME_FUTURE_CODE")
        assertEquals(R.string.err_058, info.titleRes)
        assertEquals(R.string.err_059, info.reasonRes)
        assertEquals(R.string.err_060, info.suggestionRes)
    }

    @Test
    fun `every AppError code literal in main source is in the table`() {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 main 源码目录，候选：$roots（工作目录=${File(".").absolutePath}）")
        // 错误码发射点：AppError(/onAppError(/onTransportError(/onError( 的第一个字符串参数，
        // 以及 relay 下发 code 缺失时的默认值 optString("code", "X")；允许换行
        val pattern = Regex("""(?:AppError|onAppError|onTransportError|onError)\(\s*"([A-Z0-9_]+)"|optString\("code",\s*"([A-Z0-9_]+)"""")
        val used = mutableSetOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                pattern.findAll(f.readText()).forEach { m ->
                    // 注意：groups[0] 是整个匹配串，取 groups[1]/[2] 才是捕获的错误码
                    val code = m.groups[1]?.value ?: m.groups[2]?.value ?: return@forEach
                    used.add(code)
                }
            }
        assertTrue("main 源码里没扫到任何 AppError 码，扫描可能失效", used.isNotEmpty())
        val table = ErrorCodes.allCodes()
        val missing = used - table
        // 这些错误码在 ErrorCodes 表里没有文案，用户会看到通用回退
        if (missing.isNotEmpty()) {
            throw AssertionError("missing=${missing.sorted()} used=${used.sorted()} tableSize=${table.size}")
        }
    }
}
