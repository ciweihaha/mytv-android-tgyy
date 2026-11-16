package top.yogiczy.mytv.ui.screens.leanback.panel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PanelAutoCloseStateTest {
    @Test
    fun `timeout fires once after inactivity`() = runBlocking {
        // 超时 500 毫秒：200 毫秒时还没到点、800 毫秒时已触发一次；
        // 防抖若丢了，200 毫秒那句就挂；回调若重复触发，次数就不是 1
        val fired = AtomicInteger(0)
        val state = PanelAutoCloseState(timeout = 500) { fired.incrementAndGet() }
        val job = launch { state.observe() }
        state.active()
        delay(200)
        assertEquals(0, fired.get())
        delay(600)
        assertEquals(1, fired.get())
        job.cancel()
    }

    @Test
    fun `re-activating resets the timer`() = runBlocking {
        // 500 毫秒时再 active 一次：又过 500 毫秒（距第一次 1000 毫秒）
        // 还没到点；再过 1000 毫秒才触发一次。active 若没重置计时，
        // 1000 毫秒那句就挂
        val fired = AtomicInteger(0)
        val state = PanelAutoCloseState(timeout = 1000) { fired.incrementAndGet() }
        val job = launch { state.observe() }
        state.active()
        delay(500)
        state.active()
        delay(500)
        assertEquals(0, fired.get())
        delay(1000)
        assertEquals(1, fired.get())
        job.cancel()
    }

    @Test
    fun `zero timeout fires without waiting`() = runBlocking {
        // 超时是 0：active 后不用等就触发一次
        val fired = AtomicInteger(0)
        val state = PanelAutoCloseState(timeout = 0) { fired.incrementAndGet() }
        val job = launch { state.observe() }
        state.active()
        delay(300)
        assertEquals(1, fired.get())
        job.cancel()
    }

    @Test
    fun `fires again when re-activated after it has fired`() = runBlocking {
        // 超时 200 毫秒：触发一次后、再 active 一次，应再触发一次；
        // observe 若只取一次就停，面板一辈子只自动关一次
        val fired = AtomicInteger(0)
        val state = PanelAutoCloseState(timeout = 200) { fired.incrementAndGet() }
        val job = launch { state.observe() }
        state.active()
        delay(400)
        assertEquals(1, fired.get())

        state.active()
        delay(400)
        assertEquals(2, fired.get())
        job.cancel()
    }

    @Test
    fun `rapid re-activations fire only once`() = runBlocking {
        // 遥控器连按时 active 会被连续调多次：防抖加合并通道应只
        // 触发一次——通道若改成无上限缓冲，这里会触发 5 次，面板
        // 关一次后又被重复触发；旧用例只测过隔 500 毫秒再 active，
        // 没测过短时间内的连续 active
        val fired = AtomicInteger(0)
        val state = PanelAutoCloseState(timeout = 300) { fired.incrementAndGet() }
        val job = launch { state.observe() }
        repeat(5) { state.active() }
        delay(150)
        assertEquals(0, fired.get())
        delay(450)
        assertEquals(1, fired.get())
        job.cancel()
    }
}
