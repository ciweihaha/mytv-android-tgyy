package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class LeanbackVideoPlayerCutoffTest {
    private class FakeVideoPlayer : LeanbackVideoPlayer(
        CoroutineScope(Dispatchers.Unconfined),
    ) {
        override fun prepare(url: String) = Unit
        override fun play() = Unit
        override fun pause() {
            super.pause()
        }

        override fun setVideoSurfaceView(surfaceView: SurfaceView) = Unit

        fun emitPosition(position: Long, timeoutMs: Long) =
            triggerCurrentPosition(position, timeoutMs)
    }

    @Test
    fun `cutoff still fires when the position stalls while playing`() {
        val player = FakeVideoPlayer()
        var cutoffCount = 0
        player.onCutoff { cutoffCount++ }

        player.emitPosition(1000L, timeoutMs = 100L)

        Thread.sleep(1000)
        assertEquals(1, cutoffCount)
    }

    @Test
    fun `pause cancels the pending cutoff, a frozen position does not re-prepare while paused`() {
        val player = FakeVideoPlayer()
        var cutoffCount = 0
        player.onCutoff { cutoffCount++ }

        player.emitPosition(1000L, timeoutMs = 100L)
        // 按 Home 退后台会走到这里：位置冻住，之前那次位置变化挂起的
        // 断流计时必须停掉，否则 15 秒后又会重新准备同一条流
        player.pause()

        Thread.sleep(1000)
        assertEquals(0, cutoffCount)
    }

    @Test
    fun `cutoff is re-armed after resume, a stall after resume still fires`() {
        val player = FakeVideoPlayer()
        var cutoffCount = 0
        player.onCutoff { cutoffCount++ }

        player.emitPosition(1000L, timeoutMs = 100L)
        player.pause()
        Thread.sleep(300)
        assertEquals(0, cutoffCount)

        // 回到前台恢复播放：位置一动，断流计时重新开始
        player.emitPosition(2000L, timeoutMs = 100L)
        Thread.sleep(1000)
        assertEquals(1, cutoffCount)
    }

    @Test
    fun `repeated same position does not re-arm the cutoff, a stall still fires`() {
        val player = FakeVideoPlayer()
        var cutoffCount = 0
        player.onCutoff { cutoffCount++ }

        // 位置冻住时 1 秒轮询每秒都会报同一个位置：重复上报不能重设计时，
        // 否则 15 秒超时永远等不到、断流永远报不出来
        player.emitPosition(1000L, timeoutMs = 300L)
        repeat(12) {
            Thread.sleep(50)
            player.emitPosition(1000L, timeoutMs = 300L)
        }
        // 此时约 650ms：正确行为已在 300ms 时报过一次断流；
        // 若重复上报会重设计时，计时会被推到 900ms 之后、现在应还是 0 次
        Thread.sleep(50)
        assertEquals(1, cutoffCount)
    }
}
