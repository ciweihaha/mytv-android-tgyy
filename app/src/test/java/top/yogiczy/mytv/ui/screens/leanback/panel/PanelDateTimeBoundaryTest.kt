package top.yogiczy.mytv.ui.screens.leanback.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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

    // 半小时时区：第 174 轮——整点/半点是本地墙上时间，时间戳本身是
    // UTC；直接拿 UTC 时间戳取余，在印度（+5:30）会让整点模式在当地
    // 半点时显示、整点时不显示，在尼泊尔（+5:45）连半点模式也错 15
    // 分钟。下面两组用当地时间拼时间戳验：印度整点 10:00 显示、
    // 10:30 不显示；尼泊尔半点 10:30 显示、10:15 不显示。
    // 只在单个用例里换默认时区、用完立刻换回来，不影响别的用例；
    // 印度与尼泊尔都没有夏令时，结果不受日期影响。
    private fun withTimeZone(id: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun localMillis(timeZoneId: String, hour: Int, minute: Int, second: Int = 0): Long {
        return Calendar.getInstance(TimeZone.getTimeZone(timeZoneId)).apply {
            set(2026, Calendar.NOVEMBER, 11, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `hourly period follows local hour in half-hour time zone`() {
        withTimeZone("Asia/Kolkata") {
            // 印度本地 10:00 整点显示，10:00:31 还在窗口内
            assertTrue(isNearPeriodBoundary(localMillis("Asia/Kolkata", 10, 0), 3_600_000))
            assertTrue(isNearPeriodBoundary(localMillis("Asia/Kolkata", 10, 0, 31), 3_600_000))
            // 10:30 不是当地整点：旧算法（UTC 取余）在这里会误显示
            assertFalse(isNearPeriodBoundary(localMillis("Asia/Kolkata", 10, 30), 3_600_000))
            // 10:15 在整点中间，不显示；10:59:30 在下一个整点前 30 秒内，显示
            assertFalse(isNearPeriodBoundary(localMillis("Asia/Kolkata", 10, 15), 3_600_000))
            assertTrue(isNearPeriodBoundary(localMillis("Asia/Kolkata", 10, 59, 30), 3_600_000))
        }
    }

    @Test
    fun `half-hour period follows local half hour in 45-minute time zone`() {
        withTimeZone("Asia/Kathmandu") {
            // 尼泊尔本地 10:00 与 10:30 都是半点边界，显示
            assertTrue(isNearPeriodBoundary(localMillis("Asia/Kathmandu", 10, 0), 1_800_000))
            assertTrue(isNearPeriodBoundary(localMillis("Asia/Kathmandu", 10, 30), 1_800_000))
            // 10:15 与 10:45 都在半小时中间，不显示
            assertFalse(isNearPeriodBoundary(localMillis("Asia/Kathmandu", 10, 15), 1_800_000))
            assertFalse(isNearPeriodBoundary(localMillis("Asia/Kathmandu", 10, 45), 1_800_000))
        }
    }
}
