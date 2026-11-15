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
}
