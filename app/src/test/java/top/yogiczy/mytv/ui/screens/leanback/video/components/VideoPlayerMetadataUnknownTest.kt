package top.yogiczy.mytv.ui.screens.leanback.video.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerMetadataUnknownTest {
    @Test
    fun `zero float shows unknown, nonzero shows value`() {
        // 帧率 0 是“没测到”，不是真的 0 帧；25.0 按 Float 原样显示
        assertEquals("未知", 0f.orUnknown())
        assertEquals("25.0", 25.0f.orUnknown())
    }

    @Test
    fun `zero int shows unknown, nonzero shows value`() {
        // 声道数 0 是“没测到”；负值按原样显示，归一是调用处的事
        assertEquals("未知", 0.orUnknown())
        assertEquals("2", 2.orUnknown())
        assertEquals("-1", (-1).orUnknown())
    }

    @Test
    fun `blank string shows unknown, nonempty stays as is`() {
        // 编码、解码器、色彩这些串：空串或全空白都是“没给”，原样留白会
        // 在面板上留一行空的“编码: ”，看着像缺了一块
        assertEquals("未知", "".orUnknown())
        assertEquals("未知", "   ".orUnknown())
        assertEquals("video/hevc", "video/hevc".orUnknown())
    }
}
