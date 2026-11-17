package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PrematureLiveEndTest {
    @Test
    fun `上限钉死 10 秒`() {
        assertEquals(10_000L, LeanbackMedia3VideoPlayer.MAX_PREMATURE_LIVE_END_MS)
    }

    @Test
    fun `直播流就绪后 2 秒播完要报断流`() {
        // 凤凰 FLV 源站每条连接 2–5 秒就关：就绪 2 秒就播完一定是源死了
        assertTrue(shouldReportPrematureLiveEnd(true, 1000L, 3000L))
    }

    @Test
    fun `直播流就绪后 5 秒播完要报断流`() {
        assertTrue(shouldReportPrematureLiveEnd(true, 1000L, 6000L))
    }

    @Test
    fun `直播流就绪后正好 10 秒播完要报断流`() {
        assertTrue(shouldReportPrematureLiveEnd(true, 1000L, 11_000L))
    }

    @Test
    fun `直播流就绪后超过 10 秒才播完不报、走原有断流计时`() {
        assertFalse(shouldReportPrematureLiveEnd(true, 1000L, 11_001L))
        assertFalse(shouldReportPrematureLiveEnd(true, 1000L, 61_000L))
    }

    @Test
    fun `还没就绪过不报`() {
        // prepare 后还没到 READY 就播完：加载超时 watchdog 那路处理，
        // 这里不抢——readyAtMs 是 -1，时间差算出来也是负的、别误报
        assertFalse(shouldReportPrematureLiveEnd(true, -1L, 3000L))
    }

    @Test
    fun `有总时长的点播播完不报`() {
        // 点播正常播完是预期行为：就绪时 duration 不是 TIME_UNSET，
        // readyAsLive 为 false，再快也不报
        assertFalse(shouldReportPrematureLiveEnd(false, 1000L, 3000L))
        assertFalse(shouldReportPrematureLiveEnd(false, 1000L, 1001L))
    }

    @Test
    fun `就绪时刻是 0 也算就绪过`() {
        assertTrue(shouldReportPrematureLiveEnd(true, 0L, 2000L))
    }
}
