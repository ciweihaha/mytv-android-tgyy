package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
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
}
