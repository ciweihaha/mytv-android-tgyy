package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanbackVideoPlayerLoadTimeoutTest {
    private class FakeVideoPlayer : LeanbackVideoPlayer(
        CoroutineScope(Dispatchers.Unconfined),
    ) {
        override fun prepare(url: String) = Unit
        override fun play() = Unit
        override fun pause() {
            super.pause()
        }

        override fun setVideoSurfaceView(surfaceView: SurfaceView) = Unit

        fun emitPrepared(timeoutMs: Long) = triggerPrepared(timeoutMs)
        fun emitError(error: PlaybackException?) = triggerError(error)
        fun emitReady() = triggerReady()
    }

    @Test
    fun `load timeout still fires when the stream keeps buffering`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 播流都会先进入缓冲：进缓冲只清错误显示，不能取消加载超时，
        // 否则超时到了也不会报超时换线路，细流式卡住的源一直转圈
        player.emitPrepared(timeoutMs = 100L)
        player.emitError(null)

        Thread.sleep(1000)
        assertEquals(
            listOf(LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT),
            errors,
        )
    }

    @Test
    fun `ready cancels the load timeout`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        player.emitPrepared(timeoutMs = 100L)
        player.emitReady()

        Thread.sleep(1000)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `real error cancels the load timeout, no timeout error follows`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        player.emitPrepared(timeoutMs = 100L)
        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)

        Thread.sleep(1000)
        assertEquals(
            listOf(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE),
            errors,
        )
    }

    @Test
    fun `re-prepare cancels the previous load timeout, only one timeout fires`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 切台/重试会再次 prepare：旧那次挂起的加载超时必须取消，
        // 否则旧超时会往新流里报一次多余的 LOAD_TIMEOUT
        player.emitPrepared(timeoutMs = 100L)
        Thread.sleep(50)
        player.emitPrepared(timeoutMs = 100L)

        Thread.sleep(1000)
        assertEquals(
            listOf(LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT),
            errors,
        )
    }
}
