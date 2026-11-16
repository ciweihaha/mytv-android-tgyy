package top.yogiczy.mytv.ui.screens.leanback.panel.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.DecimalFormat

class PanelPlayerInfoNetSpeedTest {
    @Test
    fun `正常采样按每秒字节数算`() {
        assertEquals(10_240L, nextNetSpeed(0, 0, 10_240, 1000))
        assertEquals(5_120L, nextNetSpeed(10_240, 10_240, 15_360, 1000))
    }

    @Test
    fun `设备不支持流量统计时返回空、界面显示未知`() {
        assertNull(nextNetSpeed(0, -1, -1, 1000))
        assertNull(nextNetSpeed(10_240, 10_240, -1, 1000))
        assertNull(nextNetSpeed(10_240, -1, 10_240, 1000))
    }

    @Test
    fun `系统时间没前进时沿用上一次的值`() {
        assertEquals(10_240L, nextNetSpeed(10_240, 0, 10_240, 0))
        assertEquals(10_240L, nextNetSpeed(10_240, 0, 10_240, -500))
        assertNull(nextNetSpeed(null, 0, 10_240, 0))
    }

    @Test
    fun `流量计数中途倒退时按 0 算、不显示负网速`() {
        assertEquals(0L, nextNetSpeed(10_240, 20_000, 10_000, 1000))
    }

    @Test
    fun `按实际耗时折算成每秒、不是只按一秒算`() {
        // 旧 4 个用例耗时全是 1000 毫秒：算式里 * 1000 / elapsedMs
        // 的折算若丢了、写死了，分母永远是 1000 也全能过；
        // 换两个不是 1000 的耗时钉住它
        assertEquals(250L, nextNetSpeed(0, 0, 500, 2000))
        assertEquals(20_480L, nextNetSpeed(0, 0, 10_240, 500))
    }

    @Test
    fun `unsupported device shows unknown instead of zero`() {
        // 设备不支持流量统计时 nextNetSpeed 回 null：显示文案应是
        // “未知”，而不是永远 0KB/s 看着像坏了
        assertEquals("网速：未知", formatNetSpeedText(null, DecimalFormat("#.#")))
    }

    @Test
    fun `kilobytes use float division with one decimal`() {
        val format = DecimalFormat("#.#")
        // 不到 1KB/s 不能截回 0：512 字节/秒应显示 0.5KB/s
        assertEquals("网速：0KB/s", formatNetSpeedText(0, format))
        assertEquals("网速：0.5KB/s", formatNetSpeedText(512, format))
        assertEquals("网速：1.5KB/s", formatNetSpeedText(1536, format))
    }

    @Test
    fun `just below the megabyte threshold still shows kilobytes`() {
        // 阈值是 1024*999：差 1 字节仍走 KB 那档，1022.999… 留一位小数进到 1023
        assertEquals("网速：1023KB/s", formatNetSpeedText(1024 * 999 - 1, DecimalFormat("#.#")))
    }

    @Test
    fun `at and above the threshold shows megabytes`() {
        val format = DecimalFormat("#.#")
        // 正好 1024*999 字节/秒：0.999…MB/s，留一位小数显示 1MB/s
        assertEquals("网速：1MB/s", formatNetSpeedText(1024 * 999, format))
        assertEquals("网速：1.5MB/s", formatNetSpeedText(1572864, format))
        assertEquals("网速：5MB/s", formatNetSpeedText(5242880, format))
    }
}
