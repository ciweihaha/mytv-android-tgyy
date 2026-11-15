package top.yogiczy.mytv.ui.screens.leanback.toast

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastProperty.Companion.toMs

class LeanbackToastStateTest {
    @Test
    fun `global instance is uninitialized before first composition`() {
        // I 只在 rememberLeanbackToastState 合成时赋值；单元测试里没
        // 有合成，所以这里一定未初始化——LeanbackActivity 回调里的
        // isInitialized 守卫就依赖这个前提
        assertFalse(LeanbackToastState.isInitialized)
    }

    @Test
    fun `duration maps to milliseconds`() {
        assertEquals(2300, LeanbackToastProperty.Duration.Default.toMs())
        assertEquals(500, LeanbackToastProperty.Duration.Custom(500).toMs())
        assertEquals(0, LeanbackToastProperty.Duration.Custom(0).toMs())
    }

    @Test
    fun `toast hides after its duration`() = runBlocking {
        // 时长 300 毫秒：刚显示时可见，700 毫秒后防抖到点、隐藏
        val state = LeanbackToastState(this)
        val job = launch { state.observe() }
        state.showToast(
            "你好",
            LeanbackToastProperty.Duration.Custom(300),
            id = "a",
        )
        delay(100)
        assertTrue(state.visible)
        assertEquals("你好", state.current.message)
        delay(600)
        assertFalse(state.visible)
        job.cancel()
    }

    @Test
    fun `stale toast does not overwrite the newest one`() = runBlocking {
        // A 显示中、B 紧接着来：B 要先等 300 毫秒换场；换场还没完 C
        // 又来了，B 醒来发现号过期了就直接退出，不能把 C 盖回去
        val state = LeanbackToastState(this)
        val job = launch { state.observe() }
        state.showToast(
            "A",
            LeanbackToastProperty.Duration.Custom(1000),
            id = "a",
        )
        delay(50)
        state.showToast(
            "B",
            LeanbackToastProperty.Duration.Custom(1000),
            id = "b",
        )
        delay(100)
        state.showToast(
            "C",
            LeanbackToastProperty.Duration.Custom(1000),
            id = "c",
        )
        delay(400)
        assertEquals("C", state.current.message)
        assertTrue(state.visible)
        delay(1200)
        assertFalse(state.visible)
        job.cancel()
    }
}
