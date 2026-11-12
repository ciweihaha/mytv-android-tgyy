package top.yogiczy.mytv.ui.screens.leanback.panel.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelIptvInfoTest {
    @Test
    fun `下标正常时原样返回`() {
        assertEquals(0, safeIptvUrlIdx(3, 0))
        assertEquals(1, safeIptvUrlIdx(3, 1))
        assertEquals(2, safeIptvUrlIdx(3, 2))
    }

    @Test
    fun `下标越上界时夹到最后一条`() {
        assertEquals(2, safeIptvUrlIdx(3, 3))
        assertEquals(2, safeIptvUrlIdx(3, 99))
    }

    @Test
    fun `下标为负时夹到第一条`() {
        assertEquals(0, safeIptvUrlIdx(3, -1))
        assertEquals(0, safeIptvUrlIdx(3, -99))
    }

    @Test
    fun `地址列表为空时返回0`() {
        assertEquals(0, safeIptvUrlIdx(0, 0))
        assertEquals(0, safeIptvUrlIdx(0, 5))
        assertEquals(0, safeIptvUrlIdx(0, -1))
    }

    @Test
    fun `只有一条地址时任何下标都夹到0`() {
        assertEquals(0, safeIptvUrlIdx(1, 0))
        assertEquals(0, safeIptvUrlIdx(1, 1))
        assertEquals(0, safeIptvUrlIdx(1, -1))
    }
}
