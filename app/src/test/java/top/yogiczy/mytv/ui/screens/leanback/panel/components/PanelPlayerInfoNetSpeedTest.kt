package top.yogiczy.mytv.ui.screens.leanback.panel.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
