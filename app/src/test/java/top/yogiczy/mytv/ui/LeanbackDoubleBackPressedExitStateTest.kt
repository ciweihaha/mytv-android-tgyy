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

    @Test
    fun `确认窗口上沿正好满 2 秒不允许退出`() {
        val s = state()
        s.backPress()
        // 窗口是 [300, 2000)：1999ms 还在窗口内、2000ms 整已出窗口
        // （until 不含上沿）。旧用例只测过 2500ms（远超窗口），上沿
        // 这一点一次也没钉过——若写成闭区间，2000ms 整也会放行，
        // 旧单测发现不了
        now += 1999
        assertTrue(s.allowExit)
        now += 1
        assertFalse(s.allowExit)
    }

    @Test
    fun `间隔正好满 300ms 的第二次按压被接受并重新计时`() {
        val s = state()
        assertTrue(s.backPress())
        now += LeanbackDoubleBackPressedExitState.MIN_GAP_MILLIS
        // 正好 300ms：小于 300 才算同一物理按键的重复投递，300 整
        // 应被接受为一次新按压、并把第一次按压的时间重记为现在。
        // 旧用例只在 allowExit 上测过 300，没在 backPress() 上测过——
        // 若 < 写成 <=，正好 300ms 的第二次按压会被当成重复投递丢掉
        assertTrue(s.backPress())
        // 刚重记完，距第一次按压 0ms，不在窗口内
        assertFalse(s.allowExit)
        now += 800
        assertTrue(s.allowExit)
    }
}
