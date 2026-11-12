package top.yogiczy.mytv.ui.screens.leanback.video

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackMedia3VideoPlayer
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

/**
 * 播放器状态
 */
@Stable
class LeanbackVideoPlayerState(
    private val instance: LeanbackVideoPlayer,
    // var 而不是 val：provider 可能捕获组合局部的 configuration（屏幕尺寸，
    // “自动拉伸”按它算比例），remember 只在首次组合建 state，之后重组
    // 若不更新，旋转后仍按首次的屏幕尺寸拉伸；remember 函数每次重组
    // 都把最新的 provider 赋进来（见下），这里只是存、调用时才求值
    var defaultAspectRatioProvider: () -> Float? = { null },
) {
    /** 视频宽高比 */
    var aspectRatio by mutableFloatStateOf(16f / 9f)

    /** 错误 */
    var error by mutableStateOf<String?>(null)

    /** 元数据 */
    var metadata by mutableStateOf(LeanbackVideoPlayer.Metadata())

    /** 最近一次收到的视频分辨率（设置里改比例后重算用） */
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    fun prepare(url: String) {
        error = null
        instance.prepare(url)
    }

    fun play() {
        instance.play()
    }

    fun pause() {
        instance.pause()
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        instance.setVideoSurfaceView(surfaceView)
    }

    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onCutoffListeners = mutableListOf<() -> Unit>()

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onError(listener: () -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onCutoff(listener: () -> Unit) {
        onCutoffListeners.add(listener)
    }

    fun initialize() {
        instance.initialize()
        instance.onResolution { width, height ->
            lastVideoWidth = width
            lastVideoHeight = height
            applyDefaultAspectRatio(width, height)
        }
        instance.onError { ex ->
            error = if (ex != null) "${ex.errorCodeName}(${ex.errorCode})"
            else null

            if (error != null) onErrorListeners.forEach { it.invoke() }

        }
        instance.onReady { onReadyListeners.forEach { it.invoke() } }
        instance.onBuffering { if (it) error = null }
        instance.onPrepared { }
        instance.onMetadata { metadata = it }
        instance.onCutoff { onCutoffListeners.forEach { it.invoke() } }
    }

    /**
     * 按当前画面比例设置重新计算并立即应用。设置里改比例后调用——
     * defaultAspectRatioProvider 只在分辨率变化（onResolution）时才被求值，
     * 不加这一处要等切台才生效；若尚未收到过分辨率事件则什么都不做
     */
    fun applyDefaultAspectRatio() {
        applyDefaultAspectRatio(lastVideoWidth, lastVideoHeight)
    }

    private fun applyDefaultAspectRatio(width: Int, height: Int) {
        val defaultAspectRatio = defaultAspectRatioProvider()

        if (defaultAspectRatio == null) {
            if (width > 0 && height > 0) aspectRatio = width.toFloat() / height
        } else {
            aspectRatio = defaultAspectRatio
        }
    }

    fun release() {
        onReadyListeners.clear()
        onErrorListeners.clear()
        onCutoffListeners.clear()
        instance.release()
    }
}

@Composable
fun rememberLeanbackVideoPlayerState(
    defaultAspectRatioProvider: () -> Float? = { null },
): LeanbackVideoPlayerState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val state = remember {
        LeanbackVideoPlayerState(
            LeanbackMedia3VideoPlayer(context, coroutineScope),
            defaultAspectRatioProvider = defaultAspectRatioProvider,
        )
    }
    // 每次重组都把最新的 provider 赋进来：remember 只建一次 state，
    // 调用方（MainContent）的 provider 捕获了当时的 configuration，
    // 旋转后重组若不更新，“自动拉伸”会一直按旧屏幕尺寸算比例；
    // 只是换一个 lambda 引用，不触发重组、不影响播放器实例
    state.defaultAspectRatioProvider = defaultAspectRatioProvider

    DisposableEffect(Unit) {
        state.initialize()

        onDispose {
            state.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.play()
            } else if (event == Lifecycle.Event.ON_STOP) {
                state.pause()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return state
}
