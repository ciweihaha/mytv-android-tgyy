package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PrematureLiveEndRetryTest {
    @Test
    fun `上限钉死 20 次、窗口 60 秒`() {
        assertEquals(20, LeanbackMedia3VideoPlayer.MAX_PREMATURE_END_RETRIES)
        assertEquals(60_000L, LeanbackMedia3VideoPlayer.PREMATURE_END_RETRY_WINDOW_MS)
    }

    @Test
    fun `窗口内第一次过早播完要重试`() {
        val (kept, giveUp) = nextPrematureEndRetry(
            emptyList(),
            now = 1000L,
            windowMs = 60_000L,
            maxRetries = 20,
        )
        assertFalse(giveUp)
        assertEquals(listOf(1000L), kept)
    }

    @Test
    fun `窗口内连着过早播完二十次都重试、第二十一次才报断流`() {
        // 凤凰 FLV 每条连接 2–5 秒就关：单次过早播完直接报 CUTOFF 会让
        // 只有一条线路的台立刻冻住；先重拉，扛住偶发断流。上限 20 次
        // 是实测值：60 秒里要连 12–20 次，5 次的话 40 秒出头就烧完、
        // 照样报 CUTOFF（v2.2.26 平板实测：30 秒还在播，65 秒已报）
        val maxRetries = LeanbackMedia3VideoPlayer.MAX_PREMATURE_END_RETRIES
        var timestamps: List<Long> = emptyList()
        var now = 1000L
        repeat(maxRetries) {
            val (kept, giveUp) = nextPrematureEndRetry(
                timestamps,
                now,
                windowMs = 60_000L,
                maxRetries = maxRetries,
            )
            assertFalse(giveUp)
            timestamps = kept
            // 间隔 2 秒：20 次要挤在 60 秒窗口里才数得到 20 次；真实
            // 节奏是 5–8 秒一次，窗口会先清掉旧的、永远数不到上限，
            // 那正是想要的（一直重连不断播），上限只防窗口内密集连断
            now += 2000L
        }
        assertEquals(maxRetries, timestamps.size)
        val (_, giveUp) = nextPrematureEndRetry(
            timestamps,
            now,
            windowMs = 60_000L,
            maxRetries = maxRetries,
        )
        assertTrue(giveUp)
    }

    @Test
    fun `窗口外的旧记录不清掉会误判、隔久了要重新数`() {
        // 播稳一阵子后偶尔一次源抖，不该和几小时前的记录叠加计数，
        // 否则播得越久越容易被一次小抖动判死刑
        val old = List(20) { 1000L + it * 1000L }
        val (kept, giveUp) = nextPrematureEndRetry(
            old,
            now = 100_000L,
            windowMs = 60_000L,
            maxRetries = 20,
        )
        assertFalse(giveUp)
        assertEquals(listOf(100_000L), kept)
    }

    @Test
    fun `窗口边界上的旧记录还算数`() {
        // filter 是 <=：正好 60 秒前的那次还在窗口内，第 21 次要报断流
        val old = List(20) { 1000L + it * 1000L }
        val (_, giveUp) = nextPrematureEndRetry(
            old,
            now = 61_000L,
            windowMs = 60_000L,
            maxRetries = 20,
        )
        assertTrue(giveUp)
    }

    @Test
    fun `重试退避短档位、第 1 2 次 1 秒、第 3 4 次 2 秒、之后 3 秒封顶`() {
        // 过早播完不共用 retryDelayMs 那档（1/2/3/5/8 秒）：连接是被
        // 源站掐的，等 8 秒不会让下一条活得更久，只会让“正在重连”
        // 占掉大半时间；重拉本身就是新连接，等 1–3 秒够了
        assertEquals(0L, prematureEndRetryDelayMs(0))
        assertEquals(1000L, prematureEndRetryDelayMs(1))
        assertEquals(1000L, prematureEndRetryDelayMs(2))
        assertEquals(2000L, prematureEndRetryDelayMs(3))
        assertEquals(2000L, prematureEndRetryDelayMs(4))
        assertEquals(3000L, prematureEndRetryDelayMs(5))
        assertEquals(3000L, prematureEndRetryDelayMs(20))
    }
}
