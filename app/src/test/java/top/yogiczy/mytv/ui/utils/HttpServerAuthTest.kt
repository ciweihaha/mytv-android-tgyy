package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpServerAuthTest {
    @Test
    fun `generated code is six digits`() {
        repeat(20) {
            val code = generateAccessCode()
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
        }
    }

    @Test
    fun `only the exact code is authorized`() {
        assertTrue(isAuthorizedAccessCode("123456", "123456"))
        assertFalse(isAuthorizedAccessCode("123457", "123456"))
        assertFalse(isAuthorizedAccessCode("", "123456"))
        assertFalse(isAuthorizedAccessCode("   ", "123456"))
        assertFalse(isAuthorizedAccessCode(null, "123456"))
    }

    @Test
    fun `five failures in a minute throttles`() {
        val now = 1_000_000L
        assertFalse(isAuthThrottled(emptyList(), now))
        assertFalse(
            isAuthThrottled(
                listOf(now - 10_000, now - 20_000, now - 30_000, now - 40_000),
                now,
            )
        )
        assertTrue(
            isAuthThrottled(
                listOf(now - 10_000, now - 20_000, now - 30_000, now - 40_000, now - 50_000),
                now,
            )
        )
        // 窗口外的旧失败不计入
        assertFalse(
            isAuthThrottled(
                listOf(now - 61_000, now - 62_000, now - 63_000, now - 64_000, now - 65_000),
                now,
            )
        )
    }
}
