package top.yogiczy.mytv.data.repositories.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class EpgTimeTest {
    // 2026-11-12 08:00 +0800 = 2026-11-12 00:00 UTC，与运行机器的时区无关
    private val eightOclockPlus0800 = 1794441600000L
    // 同一时刻写成 +0000
    private val eightOclockPlus0000 = 1794470400000L

    @Test
    fun `glued timezone without space parses to the same instant`() {
        assertEquals(eightOclockPlus0800, parseEpgTimeFallback("20261112080000+0800"))
    }

    @Test
    fun `colon timezone parses to the same instant`() {
        assertEquals(eightOclockPlus0800, parseEpgTimeFallback("20261112080000 +08:00"))
    }

    @Test
    fun `glued colon timezone parses to the same instant`() {
        assertEquals(eightOclockPlus0800, parseEpgTimeFallback("20261112080000+08:00"))
    }

    @Test
    fun `glued Z means UTC`() {
        assertEquals(eightOclockPlus0000, parseEpgTimeFallback("20261112080000Z"))
    }

    @Test
    fun `different timezone offset shifts the instant`() {
        assertEquals(eightOclockPlus0000, parseEpgTimeFallback("20261112080000 +0000"))
    }

    @Test
    fun `negative glued timezone parses to the same instant`() {
        // 美洲源的负时区：贴着秒的 -0800 只能走第一档 Z（主解析
        // 要求时区前面有空格，贴着秒的进不了主解析）；08:00-0800
        // 是 UTC 16:00，即 +0800 的次日 00:00
        assertEquals(1794499200000L, parseEpgTimeFallback("20261112080000-0800"))
    }

    @Test
    fun `negative glued colon timezone parses to the same instant`() {
        // 同上，冒号写法走第三档 XXX
        assertEquals(1794499200000L, parseEpgTimeFallback("20261112080000-08:00"))
    }

    @Test
    fun `no timezone is read as local time`() {
        val expected = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            .parse("20261112080000")!!.time
        assertEquals(expected, parseEpgTimeFallback("20261112080000"))
    }

    @Test
    fun `leading and trailing spaces are tolerated`() {
        assertEquals(eightOclockPlus0800, parseEpgTimeFallback("  20261112080000+0800  "))
    }

    @Test
    fun `garbage short and blank inputs return zero`() {
        assertEquals(0L, parseEpgTimeFallback("garbage"))
        assertEquals(0L, parseEpgTimeFallback("2026111208"))
        assertEquals(0L, parseEpgTimeFallback(""))
        assertEquals(0L, parseEpgTimeFallback("   "))
    }

    @Test
    fun `invalid dates return zero instead of rolling over`() {
        // 壳 830 轮姊妹检查：SimpleDateFormat 默认宽容模式会把非法日期
        // 顺延成有效日期（2 月 30 日算成 3 月 2 日、13 月算成次年 1 月、
        // 24 点算成次日 0 点），节目会出现在错误的那天；严格模式下
        // 解析失败记 0，由调用方按无效节目过滤
        assertEquals(0L, parseEpgTimeFallback("20260230080000+0800"))
        assertEquals(0L, parseEpgTimeFallback("20261301080000 +0800"))
        assertEquals(0L, parseEpgTimeFallback("20261112240000 +0800"))
    }

    @Test
    fun `repeated fallback calls return the same instant`() {
        // 兜底格式器改成按线程复用后：同一线程反复调同一输入，结果必
        // 须和第一次一字不变——SimpleDateFormat 复用时若带了可变状态，
        // 第二次解析就会走样
        val input = "20261112080000+08:00"
        val first = parseEpgTimeFallback(input)
        repeat(100) { assertEquals(first, parseEpgTimeFallback(input)) }
        assertEquals(eightOclockPlus0800, first)
    }
}
