package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        fun emitLoadProgress(timeoutMs: Long) = notifyLoadProgress(timeoutMs)
        fun emitCancelLoadTimeout() = cancelLoadTimeout()
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

    @Test
    fun `release cancels the pending load timeout, no timeout after release`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 切台/退出播放页时释放播放器：挂起的加载超时必须停掉，
        // 否则它会在已释放的播放器上继续报 LOAD_TIMEOUT
        player.emitPrepared(timeoutMs = 100L)
        player.release()

        Thread.sleep(1000)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `load progress restarts the timeout while data keeps flowing`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 慢启动：SCF 票据 + 列表 + 分片陆续到货，每次加载完成都给
        // 看门狗续命，200 毫秒的超时到了也不该报；停了进展才报
        player.emitPrepared(timeoutMs = 200L)
        Thread.sleep(50)
        player.emitLoadProgress(timeoutMs = 200L)
        Thread.sleep(50)
        player.emitLoadProgress(timeoutMs = 200L)
        assertTrue(errors.isEmpty())

        Thread.sleep(250)
        assertEquals(
            listOf(LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT),
            errors,
        )
    }

    @Test
    fun `load progress past the total cap does not extend the timeout forever`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 总上限是超时的 3 倍（100 毫秒 ×3 = 300 毫秒）：上限内续命，
        // 上限外再有进展也不续——细流式卡住的源不能靠零星加载无限
        // 拖下去，到点照样只报一次超时
        player.emitPrepared(timeoutMs = 100L)
        Thread.sleep(50)
        player.emitLoadProgress(timeoutMs = 100L)
        Thread.sleep(70)
        player.emitLoadProgress(timeoutMs = 100L)
        Thread.sleep(130)
        player.emitLoadProgress(timeoutMs = 100L)
        Thread.sleep(70)
        player.emitLoadProgress(timeoutMs = 100L)

        Thread.sleep(150)
        assertEquals(
            listOf(LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT),
            errors,
        )
    }

    @Test
    fun `load progress after ready does not re-arm the timeout`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 就绪后分片加载还会一直回调：那时续命等于把已取消的看门狗
        // 重新挂起来，播稳后断流就乱套了（断流归 cutoff 计时管）
        player.emitPrepared(timeoutMs = 100L)
        player.emitReady()
        player.emitLoadProgress(timeoutMs = 100L)

        Thread.sleep(300)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `cancelLoadTimeout prevents the pending timeout`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 延迟重试排队时取消旧看门狗：退避等待里它先到了会报一个假
        // LOAD_TIMEOUT，取消后等待里不再报
        player.emitPrepared(timeoutMs = 100L)
        player.emitCancelLoadTimeout()

        Thread.sleep(300)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `shouldRestartLoadTimeout allows restart within the total cap`() {
        assertTrue(shouldRestartLoadTimeout(0L, 15_000L))
        assertTrue(shouldRestartLoadTimeout(44_999L, 15_000L))
    }

    @Test
    fun `shouldRestartLoadTimeout refuses restart at or past the total cap`() {
        assertFalse(shouldRestartLoadTimeout(45_000L, 15_000L))
        assertFalse(shouldRestartLoadTimeout(60_000L, 15_000L))
    }

    @Test
    fun `listeners cleared on release do not receive later errors`() {
        val player = FakeVideoPlayer()
        val errors = mutableListOf<LeanbackVideoPlayer.PlaybackException>()
        player.onError { it?.let { errors.add(it) } }

        // 释放会清掉全部监听器：之后再触发错误也不应再回调旧监听器，
        // 否则已退出的界面还会收到已释放播放器的错误
        player.release()
        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)

        assertTrue(errors.isEmpty())
    }
}
