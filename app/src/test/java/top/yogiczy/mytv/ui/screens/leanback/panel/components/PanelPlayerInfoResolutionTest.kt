package top.yogiczy.mytv.ui.screens.leanback.panel.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPlayerInfoResolutionTest {
    @Test
    fun `unknown resolution shows unknown instead of zero by zero`() {
        // 刚切台、首帧元数据还没到时宽高都是 0，原来拼成“0×0”看着像坏了
        assertEquals("未知", formatResolution(0, 0))
        // 只一边到了也还是未知：单边 0 拼不出有效分辨率
        assertEquals("未知", formatResolution(0, 1080))
        assertEquals("未知", formatResolution(1920, 0))
    }

    @Test
    fun `known resolution keeps width by height`() {
        assertEquals("1920×1080", formatResolution(1920, 1080))
        assertEquals("720×576", formatResolution(720, 576))
    }
}
