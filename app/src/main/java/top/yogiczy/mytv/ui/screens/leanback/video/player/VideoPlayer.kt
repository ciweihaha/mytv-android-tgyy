package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable

abstract class LeanbackVideoPlayer(
    private val coroutineScope: CoroutineScope,
) : Loggable() {
    private var loadTimeoutJob: Job? = null
    private var cutoffTimeoutJob: Job? = null
    private var currentPosition = -1L

    protected var metadata = Metadata()

    open fun initialize() {
        clearAllListeners()
    }

    open fun release() {
        // 播放器释放后各超时/轮询任务都不应再跑：否则它们会在组合作用域
        // 仍存活时继续触发已清空的监听器（空操作但浪费资源），以及在
        // triggerCurrentPosition 里反复重建 cutoff 超时任务
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
        cutoffTimeoutJob?.cancel()
        cutoffTimeoutJob = null
        clearAllListeners()
    }

    abstract fun prepare(url: String)

    abstract fun play()

    /**
     * 暂停：默认实现只取消断流计时。暂停后播放位置不再前进，不取消的话，
     * 位置冻住满一个加载超时（默认 15 秒）就会触发断流、重新准备同一条
     * 流；按 Home 退后台后每 15 秒重复准备一次，白白耗电耗网。回到前台
     * 恢复播放后位置一动计时又会重新开始，不会漏判真正的断流
     */
    open fun pause() {
        cutoffTimeoutJob?.cancel()
        cutoffTimeoutJob = null
    }

    abstract fun setVideoSurfaceView(surfaceView: SurfaceView)

    private val onResolutionListeners = mutableListOf<(width: Int, height: Int) -> Unit>()
    private val onErrorListeners = mutableListOf<(error: PlaybackException?) -> Unit>()
    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onBufferingListeners = mutableListOf<(buffering: Boolean) -> Unit>()
    private val onPreparedListeners = mutableListOf<() -> Unit>()
    private val onMetadataListeners = mutableListOf<(metadata: Metadata) -> Unit>()
    private val onCutoffListeners = mutableListOf<() -> Unit>()

    private fun clearAllListeners() {
        onResolutionListeners.clear()
        onErrorListeners.clear()
        onReadyListeners.clear()
        onBufferingListeners.clear()
        onPreparedListeners.clear()
        onMetadataListeners.clear()
        onCutoffListeners.clear()
    }

    protected fun triggerResolution(width: Int, height: Int) {
        onResolutionListeners.forEach { it(width, height) }
    }

    protected fun triggerError(error: PlaybackException?) {
        onErrorListeners.forEach { it(error) }
        // 空错误只是“正在缓冲、清掉错误显示”，不能顺手取消加载超时：
        // 每次播流都会先进入缓冲状态，进缓冲就取消等于加载超时永远等
        // 不到触发——细流式卡住的源会一直转圈、永远不换线路；真正播
        // 起来后 triggerReady() 会取消它，播起来之前超时就该报超时换线路。
        // 真出错时才取消：下一轮 prepare 会重新挂超时任务
        if (error != null && error != PlaybackException.LOAD_TIMEOUT) {
            loadTimeoutJob?.cancel()
            loadTimeoutJob = null
        }
    }

    protected fun triggerReady() {
        onReadyListeners.forEach { it() }
        loadTimeoutJob?.cancel()
    }

    protected fun triggerBuffering(buffering: Boolean) {
        onBufferingListeners.forEach { it(buffering) }
    }

    // 超时可传参、默认读设置里的加载超时：和下面的 triggerCurrentPosition
    // 同一个写法，单测传个短超时就能验超时逻辑，不用碰 SP
    protected fun triggerPrepared(timeoutMs: Long = SP.videoPlayerLoadTimeout) {
        onPreparedListeners.forEach { it() }
        loadTimeoutJob?.cancel()
        loadTimeoutJob = coroutineScope.launch {
            delay(timeoutMs)
            triggerError(PlaybackException.LOAD_TIMEOUT)
        }
        cutoffTimeoutJob?.cancel()
        cutoffTimeoutJob = null
    }

    protected fun triggerMetadata(metadata: Metadata) {
        onMetadataListeners.forEach { it(metadata) }
    }

    protected fun triggerCurrentPosition(
        newPosition: Long,
        timeoutMs: Long = SP.videoPlayerLoadTimeout,
    ) {
        if (currentPosition != newPosition) {
            cutoffTimeoutJob?.cancel()
            cutoffTimeoutJob = coroutineScope.launch {
                delay(timeoutMs)
                onCutoffListeners.forEach { it() }
            }
        }
        currentPosition = newPosition
    }

    fun onResolution(listener: (width: Int, height: Int) -> Unit) {
        onResolutionListeners.add(listener)
    }

    fun onError(listener: (error: PlaybackException?) -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onBuffering(listener: (buffering: Boolean) -> Unit) {
        onBufferingListeners.add(listener)
    }

    fun onPrepared(listener: () -> Unit) {
        onPreparedListeners.add(listener)
    }

    fun onMetadata(listener: (metadata: Metadata) -> Unit) {
        onMetadataListeners.add(listener)
    }

    fun onCutoff(listener: () -> Unit) {
        onCutoffListeners.add(listener)
    }

    data class PlaybackException(
        val errorCodeName: String,
        val errorCode: Int,
    ) : Exception(errorCodeName) {
        companion object {
            val UNSUPPORTED_TYPE =
                PlaybackException("UNSUPPORTED_TYPE", 10002)
            val LOAD_TIMEOUT =
                PlaybackException("LOAD_TIMEOUT", 10003)

            /**
             * 断流：位置一直不前进。主界面同一条线路短时间内反复断流时
             * 用它报错误，不再无上限重备
             */
            val CUTOFF =
                PlaybackException("CUTOFF", 10004)
        }
    }

    /** 元数据 */
    data class Metadata(
        /** 视频编码 */
        val videoMimeType: String = "",
        /** 视频宽度 */
        val videoWidth: Int = 0,
        /** 视频高度 */
        val videoHeight: Int = 0,
        /** 视频颜色 */
        val videoColor: String = "",
        /** 视频帧率 */
        val videoFrameRate: Float = 0f,
        /** 视频比特率 */
        val videoBitrate: Int = 0,
        /** 视频解码器 */
        val videoDecoder: String = "",

        /** 音频编码 */
        val audioMimeType: String = "",
        /** 音频通道 */
        val audioChannels: Int = 0,
        /** 音频采样率 */
        val audioSampleRate: Int = 0,
        /** 音频解码器 */
        val audioDecoder: String = "",
    )
}