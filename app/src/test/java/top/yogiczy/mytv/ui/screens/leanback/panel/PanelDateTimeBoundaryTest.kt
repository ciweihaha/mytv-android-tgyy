package top.yogiczy.mytv.ui.screens.leanback.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主界面时钟的整点 / 半点显示窗口：第 168 轮把两个分支里一模一样的
 * 取余比较抽成了共用的 isNearPeriodBoundary，只差周期（1 小时 /
 * 半小时）。这里验收抽出来的那份算出来和原来写死的一模一样：
 * 周期开始后 31 秒内、周期结束前 30 秒内显示，之外不显示。
 */
class PanelDateTimeBoundaryTest {
    @Test
    fun `hourly period shows at the hour and just before it`() {
        assertTrue(isNearPeriodBoundary(0, 3_600_000))
        assertTrue(isNearPeriodBoundary(31_000, 3_600_000))
        assertTrue(isNearPeriodBoundary(3_570_000, 3_600_000))
        assertTrue(isNearPeriodBoundary(3_599_999, 3_600_000))
    }

    @Test
    fun `hourly period hides in the middle of the hour`() {
        assertFalse(isNearPeriodBoundary(31_001, 3_600_000))
        assertFalse(isNearPeriodBoundary(3_569_999, 3_600_000))
        // 半点不是整点：整点模式下半点不显示
        assertFalse(isNearPeriodBoundary(1_800_000, 3_600_000))
    }

    @Test
    fun `half-hour period shows at the half hour too`() {
        assertTrue(isNearPeriodBoundary(0, 1_800_000))
        assertTrue(isNearPeriodBoundary(1_800_000, 1_800_000))
        assertTrue(isNearPeriodBoundary(31_000, 1_800_000))
        assertTrue(isNearPeriodBoundary(1_770_000, 1_800_000))
    }

    @Test
    fun `half-hour period hides in the middle of the half hour`() {
        assertFalse(isNearPeriodBoundary(31_001, 1_800_000))
        assertFalse(isNearPeriodBoundary(1_769_999, 1_800_000))
        assertFalse(isNearPeriodBoundary(900_000, 1_800_000))
    }
}
