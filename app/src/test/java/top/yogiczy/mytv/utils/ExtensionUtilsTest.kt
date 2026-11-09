package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `surrounding whitespace is ignored`() {
        // 尾部空白不处理时最后一段 toIntOrNull 得 null 记 0，"1.4.4 " 会被算成 1.4.0
        assertEquals(0, "1.4.4 ".compareVersion("1.4.4"))
        assertEquals(0, " 1.4.4".compareVersion("1.4.4"))
        assertTrue("1.4.4 ".compareVersion("1.4.3") > 0)
        assertEquals(0, "1.4.4-beta ".compareVersion("1.4.4-beta"))
    }

    @Test
    fun `numeric pre-release segments compare numerically`() {
        assertTrue("1.4.4-rc.10".compareVersion("1.4.4-rc.2") > 0)
        assertTrue("1.4.4-canary.10".compareVersion("1.4.4-canary.2") > 0)
        assertTrue("1.4.4-alpha.1".compareVersion("1.4.4-alpha") > 0)
    }
}

class IsIPv6Test {
    @Test
    fun `bracketed ipv6 urls are ipv6`() {
        assertTrue("http://[2001:db8::1]:8080/live".isIPv6())
        assertTrue("https://[::1]/x".isIPv6())
        assertTrue("[2001:db8::1]".isIPv6())
    }

    @Test
    fun `bare ipv6 urls without brackets are ipv6`() {
        // M3U 里常见的不带方括号写法
        assertTrue("http://2408:8207:1234::1:8080/live".isIPv6())
        assertTrue("2409:8c00::1".isIPv6())
        assertTrue("http://::ffff:192.168.0.1/live".isIPv6())
    }

    @Test
    fun `ipv4 and hostnames are not ipv6`() {
        assertFalse("http://192.168.1.1:8080/live".isIPv6())
        assertFalse("http://192.168.1.1/live".isIPv6())
        assertFalse("http://example.com/live".isIPv6())
        assertFalse("http://live.example.com:8080/x".isIPv6())
        assertFalse("".isIPv6())
    }
}
