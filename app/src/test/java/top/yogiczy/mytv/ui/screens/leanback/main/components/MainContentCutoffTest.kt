package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainContentCutoffTest {
    @Test
    fun `第一次断流不放弃`() {
        val (streak, giveUp) = nextCutoffStreak(emptyList(), 1000, 90_000, 3)
        assertEquals(listOf(1000L), streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口内第 3 次断流还不放弃`() {
        val (streak, giveUp) = nextCutoffStreak(
            listOf(0, 15_000), 30_000, 90_000, 3
        )
        assertEquals(listOf(0L, 15_000L, 30_000L), streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口内第 4 次断流放弃重备`() {
        val (_, giveUp) = nextCutoffStreak(
            listOf(0, 15_000, 30_000), 45_000, 90_000, 3
        )
        assertTrue(giveUp)
    }

    @Test
    fun `窗口外的旧断流先清掉、不误判`() {
        val (streak, giveUp) = nextCutoffStreak(
            listOf(0, 15_000, 30_000), 200_000, 90_000, 3
        )
        assertEquals(listOf(200_000L), streak)
        assertFalse(giveUp)
    }

    @Test
    fun `窗口边界上的旧断流还算数`() {
        val (streak, giveUp) = nextCutoffStreak(
            listOf(0, 15_000, 30_000), 90_000, 90_000, 3
        )
        assertEquals(listOf(0L, 15_000L, 30_000L, 90_000L), streak)
        assertTrue(giveUp)
    }
}
