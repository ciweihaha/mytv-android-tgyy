package top.yogiczy.mytv.ui.screens.leanback.panel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `partial input confirms after debounce and clears`() = runBlocking {
        // 2 位要等 (4 - 2) * 1000 = 2000 毫秒才确认：防抖公式若丢了、
        // 确认会立刻触发，下面“1 秒时还没确认”这句就挂
        var confirmed: String? = null
        val state = LeanbackPanelChannelNoSelectState(onChannelNoConfirm = { confirmed = it })
        val job = launch { state.observe() }
        state.input(1)
        state.input(2)
        delay(1000)
        assertNull(confirmed)
        delay(2000)
        assertEquals("12", confirmed)
        assertEquals("", state.channelNo)
        job.cancel()
    }

    @Test
    fun `fourth digit confirms immediately and clears`() = runBlocking {
        // 第 4 位后防抖超时是 0、立即确认：若公式没按位数算，
        // 500 毫秒内拿不到确认结果
        var confirmed: String? = null
        val state = LeanbackPanelChannelNoSelectState(onChannelNoConfirm = { confirmed = it })
        val job = launch { state.observe() }
        repeat(4) { state.input(3) }
        delay(500)
        assertEquals("3333", confirmed)
        assertEquals("", state.channelNo)
        job.cancel()
    }
}
