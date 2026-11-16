package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

class LeanbackVideoPlayerStateTest {
    private class FakeVideoPlayer : LeanbackVideoPlayer(
        CoroutineScope(Dispatchers.Unconfined),
    ) {
        override fun prepare(url: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun setVideoSurfaceView(surfaceView: SurfaceView) = Unit

        fun emitResolution(width: Int, height: Int) = triggerResolution(width, height)
        fun emitError(error: LeanbackVideoPlayer.PlaybackException?) = triggerError(error)
        fun emitBuffering(buffering: Boolean) = triggerBuffering(buffering)
    }

    @Test
    fun `applyDefaultAspectRatio applies the new setting immediately without waiting for resolution change`() {
        var ratio: Float? = 16f / 9f
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { ratio }
        state.initialize()

        player.emitResolution(1920, 1080)
        assertEquals(16f / 9f, state.aspectRatio, 0.001f)

        // 设置里把比例改成 4:3：不等切台，立即重算
        ratio = 4f / 3f
        state.applyDefaultAspectRatio()
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)
    }

    @Test
    fun `applyDefaultAspectRatio with ORIGINAL provider falls back to the last received resolution`() {
        var ratio: Float? = null
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { ratio }
        state.initialize()

        player.emitResolution(1280, 720)
        assertEquals(1280f / 720f, state.aspectRatio, 0.001f)

        // 先切到固定比例，再切回原始：应按最近一次分辨率重算，而不是留在 4:3
        ratio = 4f / 3f
        state.applyDefaultAspectRatio()
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)

        ratio = null
        state.applyDefaultAspectRatio()
        assertEquals(1280f / 720f, state.aspectRatio, 0.001f)
    }

    @Test
    fun `applyDefaultAspectRatio before any resolution is a no-op for ORIGINAL provider`() {
        var ratio: Float? = null
        val state = LeanbackVideoPlayerState(FakeVideoPlayer()) { ratio }
        state.initialize()

        // 尚未收到分辨率事件，原始比例无从算起，保持默认值
        state.applyDefaultAspectRatio()
        assertEquals(16f / 9f, state.aspectRatio, 0.001f)

        // 固定比例不需要分辨率信息，可以立即应用
        ratio = 4f / 3f
        state.applyDefaultAspectRatio()
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)
    }

    @Test
    fun `replacing the aspect ratio provider takes effect on the next apply`() {
        // provider 捕获组合局部的 configuration（屏幕尺寸）：“自动拉伸”
        // 按它算比例；旋转后重组传进来新的 provider，换上后下一次
        // apply 就要按新尺寸算，而不是一直用首次组合时那份
        val state = LeanbackVideoPlayerState(FakeVideoPlayer()) { 16f / 9f }
        state.initialize()

        state.applyDefaultAspectRatio()
        assertEquals(16f / 9f, state.aspectRatio, 0.001f)

        state.defaultAspectRatioProvider = { 4f / 3f }
        state.applyDefaultAspectRatio()
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)
    }

    @Test
    fun `error maps to name and code, null error clears it without notifying`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var errorNotifications = 0
        state.onError { errorNotifications++ }

        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)
        assertEquals(1, errorNotifications)

        // 进缓冲时底层会报一次空错误清显示：只清掉 error，不应再通知一次
        player.emitError(null)
        assertNull(state.error)
        assertEquals(1, errorNotifications)
    }

    @Test
    fun `buffering start clears error, buffering end keeps it`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)

        // 开始缓冲（比如切台后重新拉流）：错误横幅清掉
        player.emitBuffering(true)
        assertNull(state.error)

        // 再报一次错、缓冲结束：错误留着，不清
        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        player.emitBuffering(false)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)
    }

    @Test
    fun `prepare clears the previous error and code before loading the new stream`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)
        assertEquals(10002, state.lastErrorCode)

        // 切台重备：旧错不能留着，否则新流还没播、错误横幅先挂在那
        state.prepare("http://example.com/live.m3u8")
        assertNull(state.error)
        assertNull(state.lastErrorCode)
    }

    @Test
    fun `release clears state listeners, later errors no longer notify`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var errorNotifications = 0
        state.onError { errorNotifications++ }

        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals(1, errorNotifications)

        // 退出播放页释放 state：之后底层再报错也不应再回调旧监听器，
        // 否则已退出的界面还会收到已释放播放器的错误
        state.release()
        player.emitError(LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT)
        assertEquals(1, errorNotifications)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)
    }
}
