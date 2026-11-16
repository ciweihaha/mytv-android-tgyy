package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

class LeanbackVideoPlayerStateTest {
    private class FakeVideoPlayer : LeanbackVideoPlayer(
        CoroutineScope(Dispatchers.Unconfined),
    ) {
        var playCalls = 0
        var pauseCalls = 0
        val prefetchedUrls = mutableListOf<String>()
        val prepareUrls = mutableListOf<String>()

        override fun prepare(url: String) {
            prepareUrls.add(url)
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {
            pauseCalls++
        }

        override fun setVideoSurfaceView(surfaceView: SurfaceView) = Unit

        override fun prefetchPlaylist(url: String) {
            prefetchedUrls.add(url)
        }

        fun emitResolution(width: Int, height: Int) = triggerResolution(width, height)
        fun emitError(error: LeanbackVideoPlayer.PlaybackException?) = triggerError(error)
        fun emitBuffering(buffering: Boolean) = triggerBuffering(buffering)
        fun emitReady() = triggerReady()
        fun emitReconnecting(reconnecting: Boolean) = triggerReconnecting(reconnecting)
        fun emitMetadata(metadata: LeanbackVideoPlayer.Metadata) = triggerMetadata(metadata)
        fun emitPosition(position: Long, timeoutMs: Long) =
            triggerCurrentPosition(position, timeoutMs)
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
    fun `resolution with zero width or height keeps the previous aspect ratio`() {
        // 壳 892 轮姊妹检查：宽或高为 0 是无效分辨率（底层刚起播、
        // 还没拿到真实尺寸时可能先报一次 0），别把比例打成 0 或无限大；
        // 之后收到有效分辨率仍要正常改。provider 传 null 走“原始比例”
        // 那条分支，守卫 `width > 0 && height > 0` 在生产代码里，
        // 但一次也没断言过
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        player.emitResolution(640, 480)
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)

        player.emitResolution(0, 1080)
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)
        player.emitResolution(640, 0)
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)
        player.emitResolution(0, 0)
        assertEquals(4f / 3f, state.aspectRatio, 0.001f)

        // 无效事件不粘住：之后的有效分辨率照常改比例
        player.emitResolution(1920, 1080)
        assertEquals(16f / 9f, state.aspectRatio, 0.001f)
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
    fun `null error also clears the last error code`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals(10002, state.lastErrorCode)

        // 进缓冲时底层报空错误清显示：错误码也要一起清掉，否则主界面
        // 仍按残留的 10002 决策换线——新流还没播就可能先换一次线路
        player.emitError(null)
        assertNull(state.error)
        assertNull(state.lastErrorCode)
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

    @Test
    fun `ready forwards to state listeners`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var readyNotifications = 0
        state.onReady { readyNotifications++ }

        // 播稳了：底层 onReady 应原样转给状态层监听器（主界面靠它清
        // 掉“正在加载”与重连提示），这档一次也没断言过
        player.emitReady()
        assertEquals(1, readyNotifications)
    }

    @Test
    fun `release clears ready listeners, later ready signals no longer notify`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var readyNotifications = 0
        state.onReady { readyNotifications++ }

        player.emitReady()
        assertEquals(1, readyNotifications)

        // 退出播放页释放 state：之后底层再报就绪也不应再回调旧监听器，
        // 否则已退出的界面还会收到已释放播放器的状态变化。先证活再断：
        // 光断释放后 0 次不够，监听器压根没注册上也会是 0（壳 930 轮教训）。
        // 壳 939 轮姊妹检查：缓冲那半天光没有对应物——状态层不对外暴露
        // 缓冲监听器，只在进缓冲时清错误；只补就绪这半
        state.release()
        player.emitReady()
        assertEquals(1, readyNotifications)
    }

    @Test
    fun `reconnecting flag follows the player reconnecting signal`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        // 延迟重试等上游恢复时界面显示“正在重连…”：开与关两档
        // 一次也没断言过
        player.emitReconnecting(true)
        assertTrue(state.isReconnecting)

        player.emitReconnecting(false)
        assertTrue(!state.isReconnecting)
    }

    @Test
    fun `metadata updates are forwarded to the state`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        // 底层报分辨率/编解码器等元数据：状态层应原样存下来供调试
        // 信息屏读，初始空元数据、收到后才有值
        assertEquals(0, state.metadata.videoWidth)
        player.emitMetadata(LeanbackVideoPlayer.Metadata(videoWidth = 1920, videoHeight = 1080))
        assertEquals(1920, state.metadata.videoWidth)
        assertEquals(1080, state.metadata.videoHeight)
    }

    @Test
    fun `cutoff forwards to state listeners`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var cutoffNotifications = 0
        state.onCutoff { cutoffNotifications++ }

        // 位置冻住满超时：底层断流应原样转给状态层监听器（主界面
        // 靠它决定同一条线路短时间内反复断流时不再重备）
        player.emitPosition(1000L, timeoutMs = 100L)
        Thread.sleep(1000)
        assertEquals(1, cutoffNotifications)
    }

    @Test
    fun `release clears cutoff listeners, later cutoffs no longer notify`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        var cutoffNotifications = 0
        state.onCutoff { cutoffNotifications++ }

        player.emitPosition(1000L, timeoutMs = 100L)
        Thread.sleep(1000)
        assertEquals(1, cutoffNotifications)

        // 退出播放页释放 state：之后底层再断流也不应再回调旧监听器，
        // 否则已退出的界面还会收到已释放播放器的断流、误触换线路。
        // 壳 929 轮姊妹检查：转发那档天光已有用例钉死（上一条），
        // 释放后不再通知这档只断过错误、没断过断流
        state.release()
        player.emitPosition(2000L, timeoutMs = 100L)
        Thread.sleep(1000)
        assertEquals(1, cutoffNotifications)
    }

    @Test
    fun `play and pause are forwarded to the player`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        // 前台恢复/退后台的播放与暂停只走状态层：转不下去播不了、
        // 停不下来，之前一次也没断言过
        state.play()
        assertEquals(1, player.playCalls)

        state.pause()
        assertEquals(1, player.pauseCalls)
    }

    @Test
    fun `prefetchPlaylist is forwarded to the player`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        // 播稳后提前拉上下相邻台的 HLS 列表只走状态层：转不下去，
        // 换台时就少不了那一次往返，这档一次也没断言过
        state.prefetchPlaylist("http://example.com/cctv1.m3u8")
        assertEquals(listOf("http://example.com/cctv1.m3u8"), player.prefetchedUrls)
    }

    @Test
    fun `repeated initialize does not double-register, error notifies only once`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()
        state.initialize()

        var errorNotifications = 0
        state.onError { errorNotifications++ }

        // initialize() 先清空播放器的监听器再重注册（首播准备时同步报的
        // 错要有人接，又不能重复接）：要是清空那步丢了，初始化两次就
        // 会挂两份桥接闭包、一次报错通知两次，主界面换线路的逻辑会被
        // 触发两次。壳 931 轮姊妹检查
        player.emitError(LeanbackVideoPlayer.PlaybackException.UNSUPPORTED_TYPE)
        assertEquals("UNSUPPORTED_TYPE(10002)", state.error)
        assertEquals(1, errorNotifications)
    }

    @Test
    fun `prepare forwards the url to the player`() {
        val player = FakeVideoPlayer()
        val state = LeanbackVideoPlayerState(player) { null }
        state.initialize()

        // 切台重备只走状态层：地址要原样落到播放器上，Fake 原先是空实现、
        // 调了也看不出来；壳 932 轮姊妹检查（快进/选轨/音量那几档天光
        // 没有对应方法，不搬）
        state.prepare("http://example.com/live.m3u8")
        assertEquals(listOf("http://example.com/live.m3u8"), player.prepareUrls)
    }
}
