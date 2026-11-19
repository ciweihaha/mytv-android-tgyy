package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3BufferDurationsTest {
    @Test
    fun `开播攒 2 秒、重缓冲攒 3 秒就续播——直播流实时到、门槛高等于冻住`() {
        // 凤凰这类约 1Mbps 的实时流，到的和播的速度差不多，攒缓冲
        // 只能按实时速度攒：重缓冲要攒 10 秒等于冻住约 8 秒才续播，
        // 卡一下变卡半天。2 秒/3 秒冻住短；HLS 按整段加载（一段
        // 6–11 秒），攒 3 秒就是等到第一段到货，不吃亏。真正的缓
        // 冲余量靠 min 缓冲（50 秒）续播后在后台继续攒，不是靠门槛
        assertEquals(2_000, LeanbackMedia3VideoPlayer.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(3_000, LeanbackMedia3VideoPlayer.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }

    @Test
    fun `重缓冲门槛比开播门槛高——续播比起播多攒一点才稳`() {
        assertTrue(
            LeanbackMedia3VideoPlayer.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS >
                LeanbackMedia3VideoPlayer.BUFFER_FOR_PLAYBACK_MS,
        )
    }
}
