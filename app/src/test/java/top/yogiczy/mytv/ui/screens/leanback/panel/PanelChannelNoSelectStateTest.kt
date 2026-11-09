package top.yogiczy.mytv.ui.screens.leanback.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelChannelNoSelectStateTest {
    @Test
    fun `input stops at four digits`() {
        val state = LeanbackPanelChannelNoSelectState()
        repeat(6) { state.input(9) }
        assertEquals("9999", state.channelNo)
    }

    @Test
    fun `input below four digits accumulates`() {
        val state = LeanbackPanelChannelNoSelectState()
        state.input(1)
        state.input(2)
        assertEquals("12", state.channelNo)
    }
}
