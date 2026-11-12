package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainContentUrlIdxTest {
    @Test
    fun `点中正在播的线路不切`() {
        assertFalse(shouldSwitchUrlIdx(0, 0))
        assertFalse(shouldSwitchUrlIdx(2, 2))
    }

    @Test
    fun `点中另一条线路就切`() {
        assertTrue(shouldSwitchUrlIdx(0, 1))
        assertTrue(shouldSwitchUrlIdx(1, 0))
    }

    @Test
    fun `从最后一条切回第一条也切`() {
        assertTrue(shouldSwitchUrlIdx(2, 0))
    }
}
