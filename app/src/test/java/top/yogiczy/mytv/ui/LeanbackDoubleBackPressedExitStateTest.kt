package top.yogiczy.mytv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanbackDoubleBackPressedExitStateTest {
    /** 可控时钟：从 10000 开始（0 表示“还没按过”，不能从 0 起） */
    private var now = 10_000L
    private fun state() = LeanbackDoubleBackPressedExitState(
        resetSeconds = 2,
        clock = { now },
    )

    @Test
    fun `单按一次不退出`() {
        val s = state()
        assertFalse(s.allowExit)
        assertTrue(s.backPress())
        assertFalse(s.allowExit)
    }

    @Test
    fun `两次按压间隔足够且在窗口内允许退出`() {
        val s = state()
        s.backPress()
        now += 800
        assertTrue(s.allowExit)
    }

    @Test
    fun `同一次物理按键拆成的两次事件不退出`() {
        val s = state()
        assertTrue(s.backPress())
        // 第二起事件 50ms 后到：同一物理按键的重复投递，直接忽略
        now += 50
        assertFalse(s.allowExit)
        assertFalse(s.backPress())
        assertFalse(s.allowExit)
    }

    @Test
    fun `快速连按第三次仍能退出`() {
        val s = state()
        s.backPress()
        now += 50
        assertFalse(s.backPress())
        now += 800
        assertTrue(s.allowExit)
    }

    @Test
    fun `超过确认窗口后按压重新开始确认`() {
        val s = state()
        s.backPress()
        now += 2500
        assertFalse(s.allowExit)
        assertTrue(s.backPress())
        assertFalse(s.allowExit)
    }

    @Test
    fun `间隔正好满 300ms 算第二次按压`() {
        val s = state()
        s.backPress()
        now += LeanbackDoubleBackPressedExitState.MIN_GAP_MILLIS
        assertTrue(s.allowExit)
    }
}
