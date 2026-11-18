package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PrematureLiveEndRetryTest {
    @Test
    fun `上限钉死 5 次、窗口 60 秒`() {
        assertEquals(5, LeanbackMedia3VideoPlayer.MAX_PREMATURE_END_RETRIES)
        assertEquals(60_000L, LeanbackMedia3VideoPlayer.PREMATURE_END_RETRY_WINDOW_MS)
    }

    @Test
    fun `窗口内第一次过早播完要重试`() {
        val (kept, giveUp) = nextPrematureEndRetry(
            emptyList(),
            now = 1000L,
            windowMs = 60_000L,
            maxRetries = 5,
        )
        assertFalse(giveUp)
        assertEquals(listOf(1000L), kept)
    }

    @Test
    fun `窗口内连着过早播完五次都重试、第六次才报断流`() {
        // 凤凰 FLV 每条连接 2–5 秒就关：单次过早播完直接报 CUTOFF 会让
        // 只有一条线路的台立刻冻住；5 次内先重拉，扛住偶发断流
        var timestamps: List<Long> = emptyList()
        var now = 1000L
        repeat(5) {
            val (kept, giveUp) = nextPrematureEndRetry(
                timestamps,
                now,
                windowMs = 60_000L,
                maxRetries = 5,
            )
            assertFalse(giveUp)
            timestamps = kept
            // 每次重拉约播 3 秒 + 1/2/3/5/8 秒退避，下一条断在几秒后
            now += 5000L
        }
        assertEquals(5, timestamps.size)
        val (_, giveUp) = nextPrematureEndRetry(
            timestamps,
            now,
            windowMs = 60_000L,
            maxRetries = 5,
        )
        assertTrue(giveUp)
    }

    @Test
    fun `窗口外的旧记录不清掉会误判、隔久了要重新数`() {
        // 播稳一阵子后偶尔一次源抖，不该和几小时前的记录叠加计数，
        // 否则播得越久越容易被一次小抖动判死刑
        val old = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val (kept, giveUp) = nextPrematureEndRetry(
            old,
            now = 100_000L,
            windowMs = 60_000L,
            maxRetries = 5,
        )
        assertFalse(giveUp)
        assertEquals(listOf(100_000L), kept)
    }

    @Test
    fun `窗口边界上的旧记录还算数`() {
        // filter 是 <=：正好 60 秒前的那次还在窗口内，第 6 次要报断流
        val old = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val (_, giveUp) = nextPrematureEndRetry(
            old,
            now = 61_000L,
            windowMs = 60_000L,
            maxRetries = 5,
        )
        assertTrue(giveUp)
    }

    @Test
    fun `重试退避按次数递增、第 5 次等 8 秒`() {
        // 重试次数就是窗口内累计的过早播完次数：第 1 次等 1 秒、
        // 第 2 次 2 秒、第 3 次 3 秒、第 4 次 5 秒、第 5 次 8 秒，
        // 与直播位置落后、瞬时网络错共用同一档位表
        assertEquals(1000L, retryDelayMs(1))
        assertEquals(2000L, retryDelayMs(2))
        assertEquals(3000L, retryDelayMs(3))
        assertEquals(5000L, retryDelayMs(4))
        assertEquals(8000L, retryDelayMs(5))
    }
}
