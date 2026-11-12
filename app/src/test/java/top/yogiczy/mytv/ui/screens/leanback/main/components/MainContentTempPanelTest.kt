package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainContentTempPanelTest {
    @Test
    fun `only one line failing is the last line`() {
        assertTrue(isLastUrlFailed(0, 1))
    }

    @Test
    fun `first lines failing are not the last line`() {
        assertFalse(isLastUrlFailed(0, 3))
        assertFalse(isLastUrlFailed(1, 3))
    }

    @Test
    fun `last line failing is the last line`() {
        assertTrue(isLastUrlFailed(2, 3))
    }

    @Test
    fun `out of range index counts as last line`() {
        // 下标越界时没有下一条可切：按末条处理，给提示条起计时
        assertTrue(isLastUrlFailed(3, 3))
    }
}
