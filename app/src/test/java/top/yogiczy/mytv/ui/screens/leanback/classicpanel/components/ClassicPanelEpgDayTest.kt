package top.yogiczy.mytv.ui.screens.leanback.classicpanel.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 经典面板节目单默认选哪天：第 175 轮把写死的“今天”改成
 * 今天有节目就选今天、没有就回落到有节目的第一天。这里验收
 * 回落的算式：今天在就选今天，今天不在就选第一天，空列表
 * 时仍回今天（调用方不会走到空列表，这里只验算式本身不崩）。
 */
class ClassicPanelEpgDayTest {
    @Test
    fun `today with programmes stays today`() {
        assertEquals(
            "周三 11-11",
            initialEpgDay(
                listOf("周二 11-10", "周三 11-11", "周四 11-12"),
                "周三 11-11",
            )
        )
    }

    @Test
    fun `stale epg without today falls back to first available day`() {
        assertEquals(
            "周一 11-09",
            initialEpgDay(
                listOf("周一 11-09", "周二 11-10"),
                "周三 11-11",
            )
        )
    }

    @Test
    fun `future only source falls back to first day`() {
        assertEquals(
            "周四 11-12",
            initialEpgDay(
                listOf("周四 11-12", "周五 11-13"),
                "周三 11-11",
            )
        )
    }

    @Test
    fun `empty day list keeps today`() {
        assertEquals("周三 11-11", initialEpgDay(emptyList(), "周三 11-11"))
    }

    @Test
    fun `single day list picks that day when today is missing`() {
        assertEquals(
            "周二 11-10",
            initialEpgDay(listOf("周二 11-10"), "周三 11-11")
        )
    }
}
