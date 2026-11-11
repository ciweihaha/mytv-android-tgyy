package top.yogiczy.mytv.ui.screens.leanback.video.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerMetadataBitrateTest {
    @Test
    fun `typical bitrate keeps one decimal`() {
        // 预览里那条 10Mbps 的流：10605096 / 1024 = 10356.539…，原来整数除
        // 显示 10356 kbps，现在 10356.5 kbps
        assertEquals("10356.5 kbps", formatVideoBitrateKbps(10605096))
    }

    @Test
    fun `low bitrate no longer truncates to zero or one`() {
        // 900 bps 原来显示 0 kbps（看着像坏了），1500 bps 原来显示 1 kbps
        assertEquals("0.9 kbps", formatVideoBitrateKbps(900))
        assertEquals("1.5 kbps", formatVideoBitrateKbps(1500))
    }

    @Test
    fun `exact multiples show no trailing zero`() {
        assertEquals("1 kbps", formatVideoBitrateKbps(1024))
        assertEquals("2 kbps", formatVideoBitrateKbps(2048))
    }
}
