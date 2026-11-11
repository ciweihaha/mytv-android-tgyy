package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `build metadata is ignored`() {
        assertEquals(0, "1.4.4+build.5".compareVersion("1.4.4"))
        assertEquals(0, "1.4.4-rc.1+build.5".compareVersion("1.4.4-rc.1+build.6"))
        assertEquals(0, "1.4.4-rc.1+build.5".compareVersion("1.4.4-rc.1"))
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
    fun `ipv6 urls with userinfo are still ipv6`() {
        // 带账号密码的地址：先去掉 @ 之前的账号密码再判，否则 [ 判不到、
        // 裸判也被账号里的字母搅黄
        assertTrue("http://user:pass@[2001:db8::1]:8080/live".isIPv6())
        assertTrue("http://user:pass@2408:8207:1234::1:8080/live".isIPv6())
        assertFalse("http://user:pass@192.168.1.1:8080/live".isIPv6())
        assertFalse("http://user:pass@example.com/live".isIPv6())
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

class NextHourStepTest {
    @Test
    fun `hour value steps by one and wraps at max`() {
        // 节目单刷新时间阈值：原来是 (v + 1) % 12，默认 2
        assertEquals(1, nextHourStep(0, 12))
        assertEquals(3, nextHourStep(2, 12))
        assertEquals(0, nextHourStep(11, 12))
    }

    @Test
    fun `millisecond value steps by one hour and wraps at max`() {
        // 直播源缓存时间：原来是 (v + 1小时) % 24小时
        val hourMs = 3_600_000L
        assertEquals(hourMs, nextHourStepMs(0, 24))
        assertEquals(4 * hourMs, nextHourStepMs(3 * hourMs, 24))
        assertEquals(0, nextHourStepMs(23 * hourMs, 24))
        // 默认就是 24 小时：原来 (24小时 + 1小时) % 24小时 = 1小时
        assertEquals(hourMs, nextHourStepMs(24 * hourMs, 24))
    }
}

/**
 * 列表初始滚动位置：第 170 轮把全 App 8 处一模一样的
 * max(0, 下标 - 2) 抽成了共用的 initialScrollIndex——当前项上方留
 * 两行，打开列表就能看到上下文。这里验收抽出来的那份算出来和原来
 * 写死的算式一模一样：中间减 2、开头夹到 0、找不到（-1）也回 0。
 */
class InitialScrollIndexTest {
    @Test
    fun `middle index leaves two rows above`() {
        assertEquals(3, initialScrollIndex(5))
        assertEquals(8, initialScrollIndex(10))
        assertEquals(0, initialScrollIndex(2))
    }

    @Test
    fun `first rows clamp to zero`() {
        assertEquals(0, initialScrollIndex(1))
        assertEquals(0, initialScrollIndex(0))
    }

    @Test
    fun `missing index goes to first row`() {
        // 下标 -1（当前项不在列表里）：原来 max(0, -1 - 2) 取 0
        assertEquals(0, initialScrollIndex(-1))
    }

    @Test
    fun `custom above count is honored`() {
        assertEquals(5, initialScrollIndex(5, above = 0))
        assertEquals(1, initialScrollIndex(1, above = 0))
        assertEquals(0, initialScrollIndex(-1, above = 0))
    }
}

/**
 * 下载/上传进度百分比：第 171 轮把 Downloader（下载更新包）和
 * HttpServer（局域网上传安装包）各写的一份百分比算式抽成了共用的
 * progressPercent——原来一个是 Long 整数算、一个是 Float 算。这里
 * 验收抽出来的那份和原来两份算出来一样：向下取整、收满是 100、
 * 总长未知时不报。
 */
class ProgressPercentTest {
    @Test
    fun `fraction truncates toward zero`() {
        assertEquals(0, progressPercent(0, 3))
        assertEquals(33, progressPercent(1, 3))
        assertEquals(66, progressPercent(2, 3))
        assertEquals(50, progressPercent(1, 2))
    }

    @Test
    fun `full download is exactly 100`() {
        // 原先 Float 版收满时可能因浮点舍入算出 99，整数算必是 100
        assertEquals(100, progressPercent(100, 100))
        assertEquals(100, progressPercent(26_214_400, 26_214_400))
        assertEquals(50, progressPercent(13_107_200, 26_214_400))
    }

    @Test
    fun `unknown total reports nothing`() {
        // 分块传输拿不到总长、非法总长：原来两处都不报进度
        assertNull(progressPercent(100, 0))
        assertNull(progressPercent(100, -1))
    }
}

/**
 * 毫秒数转中文时长：第 442 轮补——全仓 40 多个单测文件里唯一没被测到的
 * 小工具。设置页三处显示都走它（直播源缓存时间、HTTP 重试间隔、界面
 * 自动关闭延时），分档边界（60 秒、60 分钟）算错会直接显示错单位。
 */
class HumanizeMsTest {
    @Test
    fun `under one minute shows seconds`() {
        assertEquals("0秒", 0L.humanizeMs())
        assertEquals("1秒", 1_000L.humanizeMs())
        assertEquals("3秒", 3_000L.humanizeMs())
        assertEquals("15秒", 15_000L.humanizeMs())
        assertEquals("59秒", 59_000L.humanizeMs())
    }

    @Test
    fun `one minute to one hour shows minutes`() {
        assertEquals("1分钟", 60_000L.humanizeMs())
        assertEquals("5分钟", 300_000L.humanizeMs())
        assertEquals("59分钟", 3_599_000L.humanizeMs())
    }

    @Test
    fun `one hour and above shows hours`() {
        assertEquals("1小时", 3_600_000L.humanizeMs())
        assertEquals("2小时", 7_200_000L.humanizeMs())
        assertEquals("24小时", 86_400_000L.humanizeMs())
    }
}
