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

    @Test
    fun `tag prefixes are stripped before comparing`() {
        assertEquals(0, "v1.4.4".compareVersion("1.4.4"))
        assertEquals(0, "V1.4.4".compareVersion("1.4.4"))
        // 上游最新 release 的 tag 是 tv2.2.7：不去前缀会被算成 0.2.7，
        // 比当前 1.4.4 小，更新检查永远报无更新
        assertTrue("tv2.2.7".compareVersion("1.4.4") > 0)
        assertTrue("V3.0.0.238".compareVersion("1.4.4") > 0)
    }

    @Test
    fun `numeric pre-release segments compare numerically`() {
        assertTrue("1.4.4-rc.10".compareVersion("1.4.4-rc.2") > 0)
        assertTrue("1.4.4-canary.10".compareVersion("1.4.4-canary.2") > 0)
        assertTrue("1.4.4-alpha.1".compareVersion("1.4.4-alpha") > 0)
    }
}
