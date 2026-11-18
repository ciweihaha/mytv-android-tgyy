package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainContentCutoffTest {
    @Test
    fun `上限钉死 20 次`() {
        assertEquals(20, LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP)
    }

    @Test
    fun `第一次断流不放弃`() {
        val maxCutoffs = LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP
        val (streak, giveUp) = nextCutoffStreak(emptyList(), 1000, 90_000, maxCutoffs)
        assertEquals(listOf(1000L), streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口内第 20 次断流还不放弃`() {
        // 凤凰那种几秒就断的源，90 秒窗口里稳态就有约 5 次；上限 3 次
        // 72 秒就烧完、照样报 CUTOFF（v2.2.29 平板实测：64 秒还在播，
        // 72 秒已报）。20 次是稳态的 4 倍余量，正常抖动永远碰不到
        val maxCutoffs = LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP
        val old = List(19) { it * 4000L }
        val (streak, giveUp) = nextCutoffStreak(old, 76_000, 90_000, maxCutoffs)
        assertEquals(old + 76_000L, streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口内第 21 次断流放弃重备`() {
        val maxCutoffs = LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP
        val old = List(20) { it * 4000L }
        val (_, giveUp) = nextCutoffStreak(old, 80_000, 90_000, maxCutoffs)
        assertTrue(giveUp)
    }

    @Test
    fun `窗口外的旧断流先清掉、不误判`() {
        val maxCutoffs = LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP
        val old = List(20) { it * 1000L }
        val (streak, giveUp) = nextCutoffStreak(old, 200_000, 90_000, maxCutoffs)
        assertEquals(listOf(200_000L), streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口边界上的旧断流还算数`() {
        // filter 是 <=：正好 90 秒前的那次还在窗口内，第 21 次要报断流
        val maxCutoffs = LeanbackMainContentState.MAX_CUTOFFS_BEFORE_GIVE_UP
        val old = List(20) { it * 4000L }
        val (streak, giveUp) = nextCutoffStreak(old, 90_000, 90_000, maxCutoffs)
        assertEquals(old + 90_000L, streak)
        assertTrue(giveUp)
    }
}
