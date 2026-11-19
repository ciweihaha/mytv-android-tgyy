package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.view.SurfaceView
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable

/**
 * 加载超时看门狗该不该因“有数据在流动”而续命：从这次准备起的总耗
 * 时还没超过总上限（加载超时的 MAX_LOAD_TIMEOUT_TOTAL_MULTIPLIER 倍）
 * 就续，超过就不续——细流式卡住的源（几秒一个小包、永远播不起来）
 * 不能靠续命无限拖下去；总上限只管“还续不续”（超过就不续），不管
 * “报不报”：进展停了一个窗口到点照样报超时换线路。正常慢启动
 * （SCF 现取票据 + 列表 + 分片，第一帧 20–30 秒才到）都在上限内，
 * 能续到播起来。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldRestartLoadTimeout(
    elapsedSincePrepareMs: Long,
    timeoutMs: Long,
): Boolean =
    elapsedSincePrepareMs < timeoutMs * LeanbackVideoPlayer.MAX_LOAD_TIMEOUT_TOTAL_MULTIPLIER

abstract class LeanbackVideoPlayer(
    private val coroutineScope: CoroutineScope,
) : Loggable() {
    private var loadTimeoutJob: Job? = null
    private var cutoffTimeoutJob: Job? = null
    private var currentPosition = -1L

    /**
     * 本轮加载超时计时的起点（triggerPrepared 时记）：加载有进展时
     * 看门狗续命要按它算总耗时，超过总上限就不续了（见
     * shouldRestartLoadTimeout）
     */
    private var loadTimeoutStartedAtMs: Long = 0L

    companion object {
        /**
         * 加载超时看门狗续命的总上限倍数：从一次 prepare 起，只有总耗时
         * 不到加载超时的 3 倍时进展才续命；最后一次续命还能再等一个超时
         * 窗口，总等待最长不到 4 倍，进展停了一个窗口到点照样报超时。
         * （默认 30 秒 ×3 = 90 秒内还允许续命。）3 倍不是拍脑袋：
         * SCF 现取票据的 302 回包实测最慢 10 秒出头，再加列表和分片、
         * 平板弱网弱 SoC，第一帧 20–30 秒才到是正常慢启动；90 秒窗
         * 口足够盖住它，又不至于让细流式卡住的源无限拖下去
         */
        const val MAX_LOAD_TIMEOUT_TOTAL_MULTIPLIER = 3
    }

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

    /**
     * 预取播放列表（P2）：默认空实现，子类可覆写。只拉列表不拉分片，
     * 失败时静默丢弃——换台时正常拉取，不影响播放。
     */
    open fun prefetchPlaylist(url: String) {}

    abstract fun play()

    /**
     * 暂停：默认实现只取消断流计时。暂停后播放位置不再前进，不取消的话，
     * 位置冻住满一个加载超时（默认 30 秒）就会触发断流、重新准备同一条
     * 流；按 Home 退后台后每 30 秒重复准备一次，白白耗电耗网。回到前台
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
    private val onReconnectingListeners = mutableListOf<(reconnecting: Boolean) -> Unit>()

    private fun clearAllListeners() {
        onResolutionListeners.clear()
        onErrorListeners.clear()
        onReadyListeners.clear()
        onBufferingListeners.clear()
        onPreparedListeners.clear()
        onMetadataListeners.clear()
        onCutoffListeners.clear()
        onReconnectingListeners.clear()
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

    /**
     * 正在重连（延迟重试/自动重调等待中）：等上游恢复的那几秒到十几秒
     * 里画面是冻住的，播失败弹窗又还没出来，用户看不出是在恢复还是
     * 卡死了；发这个信号让界面显示“正在重连…”。重连开火、播稳、
     * 报错、切台、释放时都要发 false 清掉，和 triggerBuffering 同款写法
     */
    protected fun triggerReconnecting(reconnecting: Boolean) {
        onReconnectingListeners.forEach { it(reconnecting) }
    }

    // 超时可传参、默认读设置里的加载超时：和下面的 triggerCurrentPosition
    // 同一个写法，单测传个短超时就能验超时逻辑，不用碰 SP
    protected fun triggerPrepared(timeoutMs: Long = SP.videoPlayerLoadTimeout) {
        onPreparedListeners.forEach { it() }
        loadTimeoutJob?.cancel()
        loadTimeoutStartedAtMs = System.currentTimeMillis()
        loadTimeoutJob = coroutineScope.launch {
            delay(timeoutMs)
            triggerError(PlaybackException.LOAD_TIMEOUT)
        }
        cutoffTimeoutJob?.cancel()
        cutoffTimeoutJob = null
    }

    /**
     * 加载有进展（一次加载完成）：数据在流动就不是失败，给看门狗续命。
     * 慢启动的源（SCF 现取票据 302 实测最慢 10 秒出头，再加列表和分
     * 片）按老写法 15 秒必报 LOAD_TIMEOUT——看门狗只认“准备后多久
     * 没就绪”，不管加载有没有进展；续命只是每次有进展就重起一个超
     * 时窗口，进展停了一个窗口到点照样报超时，总上限只管“还续不续”
     * （超过就不续），不管“报不报”。续命时顺手清掉之前误报的超
     * 时显示：数据都流过来了，“播放失败”再挂着就是错的。
     * 只有看门狗还挂着时才续：就绪后分片加载还会一直回调，那时续命
     * 等于把已取消的看门狗重新挂起来，播稳后断流就乱套了（断流归
     * cutoff 计时管）。总上限外的进展不续：细流式卡住的源不能靠
     * 零星加载无限续命（见 shouldRestartLoadTimeout）。
     * 超时可传参、默认读设置里的加载超时，和 triggerPrepared 同一个
     * 写法，单测传个短超时就能验续命逻辑，不用碰 SP
     */
    protected fun notifyLoadProgress(timeoutMs: Long = SP.videoPlayerLoadTimeout) {
        if (loadTimeoutJob?.isActive != true) return
        val elapsedMs = System.currentTimeMillis() - loadTimeoutStartedAtMs
        if (!shouldRestartLoadTimeout(elapsedMs, timeoutMs)) return
        loadTimeoutJob?.cancel()
        loadTimeoutJob = coroutineScope.launch {
            delay(timeoutMs)
            triggerError(PlaybackException.LOAD_TIMEOUT)
        }
        // 有进展就不是失败：清掉之前误报的超时显示（空错误只清显示，
        // 不触发换线那路，见 triggerError）
        triggerError(null)
    }

    /**
     * 取消加载超时看门狗：延迟重试排队时旧看门狗还在跑，退避没等完
     * 它先到了就报一个假 LOAD_TIMEOUT；排队时直接取消，重试开火后
     * triggerPrepared() 会重新挂，不会漏掉超时判断。释放/真报错那路
     * 已有各自的取消，这里只给延迟重试排队用
     */
    protected fun cancelLoadTimeout() {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
    }

    /**
     * 断流计时取消：直播流提前播完（STATE_ENDED）按断流上报后，位置轮询
     * 停之前挂的断流任务还在排队——30 秒后它一到就重备同一条死线，
     * 把刚报出的错误又盖掉、回到 2 秒一播的死循环。报断流前先取消它；
     * 播完后（ENDED）位置不再动，也不会再有新的断流任务。
     */
    protected fun cancelCutoffTimeout() {
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

    fun onReconnecting(listener: (reconnecting: Boolean) -> Unit) {
        onReconnectingListeners.add(listener)
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