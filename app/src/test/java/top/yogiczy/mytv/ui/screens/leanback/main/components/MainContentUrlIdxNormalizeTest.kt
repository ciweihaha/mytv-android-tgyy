package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MainContentUrlIdxNormalizeTest {
    @Test
    fun `下标正常时原样返回`() {
        assertEquals(0, normalizeIptvUrlIdx(0, 3))
        assertEquals(1, normalizeIptvUrlIdx(1, 3))
        assertEquals(2, normalizeIptvUrlIdx(2, 3))
    }

    @Test
    fun `下标减一绕到最后一条`() {
        assertEquals(2, normalizeIptvUrlIdx(-1, 3))
        assertEquals(0, normalizeIptvUrlIdx(-1, 1))
    }

    @Test
    fun `下标等于条数绕回第一条`() {
        assertEquals(0, normalizeIptvUrlIdx(3, 3))
        assertEquals(0, normalizeIptvUrlIdx(1, 1))
    }
}
