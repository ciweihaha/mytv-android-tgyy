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
}
