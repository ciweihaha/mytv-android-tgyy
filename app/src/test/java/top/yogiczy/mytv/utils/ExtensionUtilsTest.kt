package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareVersionTest {
    @Test
    fun `equal versions compare to zero`() {
        assertEquals(0, "1.4.4".compareVersion("1.4.4"))
        assertEquals(0, "1.4".compareVersion("1.4.0"))
    }

    @Test
    fun `newer version is greater`() {
        assertTrue("2.0.0".compareVersion("1.9.9") > 0)
        assertTrue("1.4.5".compareVersion("1.4.4") > 0)
        assertTrue("1.10.0".compareVersion("1.9.0") > 0)
    }

    @Test
    fun `older version is smaller`() {
        assertTrue("1.4.3".compareVersion("1.4.4") < 0)
        assertTrue("1.9.9".compareVersion("2.0.0") < 0)
    }

    @Test
    fun `release is greater than pre-release of the same version`() {
        assertTrue("1.4.4".compareVersion("1.4.4-beta") > 0)
        assertTrue("1.4.4-beta".compareVersion("1.4.4") < 0)
    }

    @Test
    fun `pre-release labels compare lexicographically`() {
        assertTrue("1.4.4-beta".compareVersion("1.4.4-alpha") > 0)
        assertEquals(0, "1.4.4-beta".compareVersion("1.4.4-beta"))
    }

    @Test
    fun `non-numeric segments do not throw`() {
        assertEquals(0, "1.4.x".compareVersion("1.4.0"))
        assertEquals(0, "1.4.4".compareVersion("1.4.4"))
    }
}
