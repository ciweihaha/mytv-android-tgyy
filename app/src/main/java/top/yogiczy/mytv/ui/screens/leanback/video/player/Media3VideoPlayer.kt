package top.yogiczy.mytv.ui.screens.leanback.video.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yogiczy.mytv.BuildConfig
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.ui.utils.SP
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import androidx.media3.common.PlaybackException as Media3PlaybackException

/**
 * 只用于媒体播放的信任全部证书管理器。
 * 部分用户配置的直播源使用自签/过期证书，播放器必须能播；但它只作用于播放器自己的
 * OkHttp 客户端，不再像以前那样替换进程全局的 HttpsURLConnection 默认值
 * （以前的 UnsafeTrustManager 会让进程内所有 HTTPS 连接都跳过证书校验）。
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object TrustAllCertsManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // 信任全部证书（仅限媒体播放）
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // 信任全部证书（仅限媒体播放）
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Media3 的 Format 用 NO_VALUE（-1）表示未知：直接进 Metadata 会在调试
 * 信息里显示成 "-1.0" 帧率、"-1" 声道数、"-1 Hz" 采样率、"-1x-1" 分辨
 * 率。归一到 Metadata 的默认值 0（0 即未知，与未收到元数据时一致）。
 * 顶层函数方便单元测试直接调用。
 */
internal fun Int.noValueToZero(): Int = if (this == Format.NO_VALUE) 0 else this

internal fun Float.noValueToZero(): Float = if (this == Format.NO_VALUE.toFloat()) 0f else this

/**
 * 播完/回到空闲时位置轮询该不该停：READY 时起的 1 秒轮询只在播放
 * 中才有意义——流一播完（STATE_ENDED，如有限时长的源）位置就
 * 再也不会变，轮询每秒空转、还一直占着协程；回到空闲（STATE_IDLE）
 * 时同理。原先只在 prepare/release/再次 READY 时才停，播完不切
 * 台就一直空转到下次切台。BUFFERING/READY 不停：缓冲时位置
 * 轮询还要继续盯断流。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldStopPositionPolling(playbackState: Int): Boolean =
    playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE

/**
 * 容器解析失败后下一个要试的类型：按 HLS、普通、RTSP 的顺序，
 * 三种都试过返回 null，上层上报错误走换线。
 * 顶层函数方便单元测试直接调用。
 */
internal fun nextUntriedContentType(attempts: Map<Int, Boolean>): Int? = when {
    attempts[C.CONTENT_TYPE_HLS] != true -> C.CONTENT_TYPE_HLS
    attempts[C.CONTENT_TYPE_OTHER] != true -> C.CONTENT_TYPE_OTHER
    attempts[C.CONTENT_TYPE_RTSP] != true -> C.CONTENT_TYPE_RTSP
    else -> null
}

/**
 * 瞬时网络错：拉列表或分片时 HTTP 回了错误状态（上游一抖回 503、
 * 票据过期那一下回 403）、连接失败或超时。ExoPlayer 内部已重试
 * 过几次才上报，但直接判死刑会让只有一条线路的台永久卡死；
 * 同 BEHIND_LIVE_WINDOW 的模式，在同一条线路上再试几次。
 * 顶层函数方便单元测试直接调用。
 */
internal fun isTransientIoError(errorCode: Int): Boolean =
    errorCode == Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        errorCode == Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

/**
 * 瞬时网络错该不该在同一条线路上再试一次：是瞬时网络错、且这条
 * 线路上已经试过的次数还没到上限。retriesSoFar 是这条线路上已经
 * 试过的次数。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldRetryTransientIoError(errorCode: Int, retriesSoFar: Int): Boolean =
    isTransientIoError(errorCode) && retriesSoFar < LeanbackMedia3VideoPlayer.MAX_IO_ERROR_RETRIES

/**
 * 第几次延迟重试前等多久：第 1 次 1 秒、第 2 次 2 秒、第 3 次 3 秒、
 * 第 4 次 5 秒、第 5 次 8 秒（斐波那契式递增，总等待约 19 秒）。
 * 上游票据轮换的毛刺经常超过 6 秒，旧的 3 次（1/2/3 秒）会在毛刺
 * 里全部烧完；拉长到 5 次、窗口约 19 秒才能扛住更长的轮换。
 * 瞬时网络错和直播位置落后两条延迟重试共用这一档位表。
 * 调用处先把重试计数加一再算，入参就是第几次重试；等多久是纯算式，
 * 抽出来方便单元测试直接调用、锁死档位，免得以后改了倍数或先加后算
 * 的顺序、重试节奏悄悄变了没人知道。
 */
internal fun retryDelayMs(retryNumber: Int): Long = when {
    retryNumber <= 0 -> 0L
    retryNumber == 1 -> 1000L
    retryNumber == 2 -> 2000L
    retryNumber == 3 -> 3000L
    retryNumber == 4 -> 5000L
    else -> 8000L
}

/**
 * 直播位置落后该不该在同一条线路上再试一次：是 BEHIND_LIVE_WINDOW、
 * 且这条线路上已经试过的次数还没到上限（MAX_BEHIND_LIVE_WINDOW_RETRIES = 5，
 * 最多重试 5 次，与瞬时网络错同档）。调用处先判后加，入参是“已重试次数”。
 * 顶层函数方便单元测试直接调用，锁死上限；同款写法见瞬时网络错那支。
 */
internal fun shouldRetryBehindLiveWindow(errorCode: Int, retriesSoFar: Int): Boolean =
    errorCode == Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
        retriesSoFar < LeanbackMedia3VideoPlayer.MAX_BEHIND_LIVE_WINDOW_RETRIES

@OptIn(UnstableApi::class)
class LeanbackMedia3VideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : LeanbackVideoPlayer(coroutineScope) {
    private val videoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    ).build().apply {
        playWhenReady = true
    }

    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()
    private var updatePositionJob: Job? = null

    /**
     * 实测帧率：VideoFrameMetadataListener 每渲染一帧回调一次，1 秒
     * 采样一次就是实测帧率；tag 里没给帧率时（0）用它补。回调在播放
     * 线程、采样在协程里，各一侧读写，用 AtomicInteger
     */
    private val renderedFrameCount = AtomicInteger(0)

    /**
     * 直播位置落后重试计数：BEHIND_LIVE_WINDOW 原先无条件 seek + prepare
     * 死循环——过期不再更新的 HLS 列表会永远缓冲在同一条线路上，既不
     * 显示错误也不切下一条线路；超过上限就上报错误走正常换线流程
     */
    private var behindLiveWindowRetries = 0

    /**
     * 瞬时网络错重试计数：与 BEHIND_LIVE_WINDOW 同模式——同一条线路
     * 最多重试 5 次，播稳（READY）后清零；超限上报原始错误走正常换线流程
     */
    private var ioErrorRetries = 0

    /**
     * 延迟重试任务：瞬时网络错与直播位置落后共用。出错后不等就立刻重拉，
     * 几次会在几毫秒内烧完——上游故障（分片签名刚过期、CDN 抖动、SCF 换票、
     * 列表过期）一般持续一两秒，立刻重拉撞上的还是同一个故障。改成可取消的
     * 协程任务，按重试次数递增等待（第 1 次 1 秒、第 2 次 2 秒、第 3 次 3 秒、
     * 第 4 次 5 秒、第 5 次 8 秒）再重拉，给上游留出恢复时间；切台/播稳/
     * 释放时取消，避免旧任务在新台上误触发。同一时刻最多一个延迟重试在
     * 排队：报错只发生在空闲态，新一轮 prepare 会先取消旧任务
     */
    private var delayedRetryJob: Job? = null

    companion object {
        const val MAX_BEHIND_LIVE_WINDOW_RETRIES = 5
        const val MAX_IO_ERROR_RETRIES = 5

        /**
         * P7：SSLContext 只建一次：首次建时 SSLContext.init 配全新
         * SecureRandom 可能在低端 SoC 上因熵池阻塞，拖慢首台起播；
         * 单例后切台/重建数据源工厂都不再重建。OkHttpClient 仍按设置变化重建。
         */
        private val sharedSslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(TrustAllCertsManager), SecureRandom())
            }
        }
    }

    /**
     * 数据源工厂缓存：原先每次 prepare（每次切台）都新建 SSLContext +
     * OkHttpClient + DefaultDataSource.Factory；按（加载超时，UA）缓存，
     * 设置改了才重建，切台直接复用
     */
    private var cachedDataSourceFactory: DataSource.Factory? = null
    private var cachedDataSourceFactoryKey: Pair<Long, String>? = null

    private fun getDataSourceFactory(): DataSource.Factory {
        val key = SP.videoPlayerLoadTimeout to SP.videoPlayerUserAgent
        val cached = cachedDataSourceFactory
        if (cached != null && cachedDataSourceFactoryKey == key) return cached
        // 直接返回新建的工厂并顺手缓存：原先先赋值再 `!!` 取回，
        // 全仓唯一的非空断言，后续改这里的条件一不小心就会 NPE，
        // 让编译器直接保证非空
        cachedDataSourceFactoryKey = key
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory())
            .also { cachedDataSourceFactory = it }
    }

    /**
     * 播放器专用的 OkHttp 客户端（随数据源工厂一起建）：预取用同一个
     * 客户端，连接池复用，换台时 TCP/TLS 已是热的；首次 prepare 前还
     * 没建出来时回落到共用客户端。
     */
    private var playerHttpClient: OkHttpClient? = null

    /**
     * 已预取过的列表地址：换台时相邻台的地址不变就不再重复拉；
     * 上限 64 条，满了清空重记，避免无限增长
     */
    private val prefetchedPlaylistUrls = mutableSetOf<String>()

    /**
     * 预取相邻台的 HLS 列表（P2）：当前台播稳后提前拉，换台时少一次
     * DNS + TCP + TLS + 请求往返。只拉 master 列表（几 KB），不拉分片；
     * 只认 .m3u8 地址，其他类型不预取。用播放器自己的 OkHttp 客户端，
     * 连接池与真正播放复用；失败时允许下次再试。
     */
    override fun prefetchPlaylist(url: String) {
        if (!url.contains(".m3u8", ignoreCase = true)) return
        synchronized(prefetchedPlaylistUrls) {
            if (!prefetchedPlaylistUrls.add(url)) return
            if (prefetchedPlaylistUrls.size > 64) prefetchedPlaylistUrls.clear()
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val client = playerHttpClient ?: sharedHttpClient
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    // 只预热连接，不取 body 内容；close 即丢弃
                    response.body?.close()
                }
            } catch (_: Exception) {
                // 预取失败不影响播放：换台时正常拉取；允许下次再试
                synchronized(prefetchedPlaylistUrls) { prefetchedPlaylistUrls.remove(url) }
            }
        }
    }

    /**
     * 播放器专用的 HTTP 数据源工厂：用独立的 OkHttp 客户端拉流，证书校验只对播放流量放行。
     * 超时、User-Agent 与原 DefaultHttpDataSource 配置保持一致；
     * OkHttp 默认跟随重定向（含跨协议），与原 setAllowCrossProtocolRedirects(true) 等效。
     */
    @OptIn(UnstableApi::class)
    private fun createHttpDataSourceFactory(): DataSource.Factory {
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sharedSslContext.socketFactory, TrustAllCertsManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(SP.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(SP.videoPlayerLoadTimeout, TimeUnit.MILLISECONDS)
            .build()
            .also { playerHttpClient = it }
        return OkHttpDataSource.Factory(okHttpClient).apply {
            setUserAgent(SP.videoPlayerUserAgent)
        }
    }

    @OptIn(UnstableApi::class)
    private fun prepare(uri: Uri, contentType: Int? = null) {
        val dataSourceFactory = getDataSourceFactory()

        val mediaItem = MediaItem.fromUri(uri)

        // 源类型只推断一次：原先 when 里算一次、记尝试表时又算一次，
        // 同一个地址两次推断结果必然相同
        val resolvedContentType = contentType ?: Util.inferContentType(uri)
        val mediaSource = when (resolvedContentType) {
            C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_RTSP -> {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }

            else -> {
                triggerError(
                    PlaybackException.UNSUPPORTED_TYPE.copy(
                        errorCodeName = "${PlaybackException.UNSUPPORTED_TYPE.message}_$resolvedContentType"
                    )
                )
                null
            }
        }

        if (mediaSource != null) {
            contentTypeAttempts[resolvedContentType] = true
            videoPlayer.setMediaSource(mediaSource)
            videoPlayer.prepare()
            triggerPrepared()
        }
        updatePositionJob?.cancel()
        updatePositionJob = null
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            triggerResolution(videoSize.width, videoSize.height)
        }

        override fun onPlayerError(ex: Media3PlaybackException) {
            // 如果是直播加载位置错误，先带等待重试几次；一直落后说明该线路的
            // 列表已过期不再更新，继续重试只是无限缓冲，交给上层换线。
            // 重试带递增等待：原先立即 seek + prepare 连试，列表过期时
            // 几毫秒内就烧完上限、直接报 BEHIND_LIVE_WINDOW；列表跳变
            // （票据轮换那一下）一般一两秒就恢复，等一等再重拉才有意义
            if (ex.errorCode == Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // 先判后加：behindLiveWindowRetries 是“已重试次数”，没到
                // 上限（5 次）才再试一次；到上限才上报原始错误走换线
                if (shouldRetryBehindLiveWindow(ex.errorCode, behindLiveWindowRetries)) {
                    behindLiveWindowRetries++
                    triggerReconnecting(true)
                    delayedRetryJob?.cancel()
                    delayedRetryJob = coroutineScope.launch {
                        delay(retryDelayMs(behindLiveWindowRetries))
                        triggerReconnecting(false)
                        videoPlayer.seekToDefaultPosition()
                        videoPlayer.prepare()
                    }
                } else {
                    behindLiveWindowRetries = 0
                    triggerReconnecting(false)
                    triggerError(
                        PlaybackException(ex.errorCodeName, ex.errorCode)
                    )
                }
            }
            // 瞬时网络错（2004/2001/2002）：上游抖动、票据过期瞬间或
            // 连接超时，同一条线路再拉一次列表能拿到新票据；直接判死
            // 刑会让只有一条线路的台永久卡死。重试 5 次还不行再上报
            // 原始错误走正常换线流程。重试带递增等待：上游故障是时间
            // 型的，立刻重拉撞上的还是同一个故障，几次会在几毫秒内烧
            // 完；第 1 次等 1 秒、第 2 次等 2 秒、第 3 次等 3 秒、第 4 次
            // 等 5 秒、第 5 次等 8 秒再重拉，给上游恢复时间
            else if (isTransientIoError(ex.errorCode)) {
                if (shouldRetryTransientIoError(ex.errorCode, ioErrorRetries)) {
                    ioErrorRetries++
                    triggerReconnecting(true)
                    delayedRetryJob?.cancel()
                    delayedRetryJob = coroutineScope.launch {
                        delay(retryDelayMs(ioErrorRetries))
                        triggerReconnecting(false)
                        videoPlayer.seekToDefaultPosition()
                        videoPlayer.prepare()
                    }
                } else {
                    ioErrorRetries = 0
                    triggerReconnecting(false)
                    triggerError(
                        PlaybackException(ex.errorCodeName, ex.errorCode)
                    )
                }
            }
            // 当解析容器不支持时，尝试使用其他解析容器
            else if (ex.errorCode == Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                val uri = videoPlayer.currentMediaItem?.localConfiguration?.uri
                val nextType = nextUntriedContentType(contentTypeAttempts)
                if (uri != null && nextType != null) {
                    prepare(uri, nextType)
                } else {
                    // 地址拿不到（报错时媒体源已被换掉），或三种容器都试过：
                    // 别悄悄吞掉，上报错误走正常换线，否则界面一直黑屏、
                    // 也不切下一条线路
                    triggerError(PlaybackException.UNSUPPORTED_TYPE)
                }
            } else {
                triggerError(
                    PlaybackException(ex.errorCodeName, ex.errorCode)
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                triggerError(null)
                triggerBuffering(true)
            } else if (playbackState == Player.STATE_READY) {
                // 播起来了：位置落后计数和瞬时网络错计数清零，下次再出错重新数；
                // 挂起的延迟重试任务也取消（已播稳就不需要它了），重连提示清掉
                behindLiveWindowRetries = 0
                ioErrorRetries = 0
                delayedRetryJob?.cancel()
                delayedRetryJob = null
                triggerReconnecting(false)
                triggerReady()

                updatePositionJob?.cancel()
                updatePositionJob = coroutineScope.launch {
                    triggerCurrentPosition(-1)
                    while (true) {
                        triggerCurrentPosition(videoPlayer.currentPosition)
                        // tag 里没给帧率时（0）用实测值补：1 秒内渲染的帧
                        // 数；窗口比 1 秒略长，值是近似值，调试信息够用；
                        // 卡顿时帧数为 0 就留着旧值，别把帧率清成 0
                        val frames = renderedFrameCount.getAndSet(0)
                        if (metadata.videoFrameRate == 0f && frames > 0) {
                            metadata = metadata.copy(videoFrameRate = frames.toFloat())
                            triggerMetadata(metadata)
                        }
                        delay(1000)
                    }
                }
            }

            if (playbackState != Player.STATE_BUFFERING) {
                triggerBuffering(false)
            }

            // 播完/回到空闲：位置轮询再跑下去只是每秒空转（位置已
            // 不会再变），停掉它；下次 prepare 或再次 READY 时再起
            if (shouldStopPositionPolling(playbackState)) {
                updatePositionJob?.cancel()
                updatePositionJob = null
            }
        }
    }

    private val metadataListener = @UnstableApi object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(
                videoMimeType = format.sampleMimeType ?: "",
                videoWidth = format.width.noValueToZero(),
                videoHeight = format.height.noValueToZero(),
                videoColor = format.colorInfo?.toLogString() ?: "",
                // 比特率目前仍从 tag 里取，有的返回空，后续需要实时计算；
                // 帧率已在 1 秒采样循环里用实测渲染帧数补（tag 缺失时）
                videoFrameRate = format.frameRate.noValueToZero(),
                videoBitrate = format.bitrate.noValueToZero(),
            )
            triggerMetadata(metadata)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(videoDecoder = decoderName)
            triggerMetadata(metadata)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            metadata = metadata.copy(
                audioMimeType = format.sampleMimeType ?: "",
                audioChannels = format.channelCount.noValueToZero(),
                audioSampleRate = format.sampleRate.noValueToZero(),
            )
            triggerMetadata(metadata)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            metadata = metadata.copy(audioDecoder = decoderName)
            triggerMetadata(metadata)
        }
    }

    private val eventLogger = EventLogger()

    override fun initialize() {
        super.initialize()
        videoPlayer.addListener(playerListener)
        videoPlayer.addAnalyticsListener(metadataListener)
        // P3：EventLogger 只在 debug 构建挂载：它把每次播放器事件都详细
        // 打到 logcat，release 包不需要这份噪音和 I/O；debug 信息不受影响
        if (BuildConfig.DEBUG) {
            videoPlayer.addAnalyticsListener(eventLogger)
        }
        videoPlayer.setVideoFrameMetadataListener { _, _, _, _ ->
            renderedFrameCount.incrementAndGet()
        }
        // 不用清监听器：setVideoFrameMetadataListener 只接受非空，
        // 且 release() 里 videoPlayer.release() 后它再也不会回调
    }

    override fun release() {
        delayedRetryJob?.cancel()
        delayedRetryJob = null
        triggerReconnecting(false)
        updatePositionJob?.cancel()
        updatePositionJob = null
        videoPlayer.removeListener(playerListener)
        videoPlayer.removeAnalyticsListener(metadataListener)
        videoPlayer.removeAnalyticsListener(eventLogger)
        videoPlayer.release()
        super.release()
    }

    @UnstableApi
    override fun prepare(url: String) {
        contentTypeAttempts.clear()
        behindLiveWindowRetries = 0
        ioErrorRetries = 0
        delayedRetryJob?.cancel()
        delayedRetryJob = null
        triggerReconnecting(false)
        prepare(Uri.parse(url))
    }

    override fun play() {
        videoPlayer.play()
    }

    override fun pause() {
        // 先走基类默认实现取消断流计时（见基类 pause），再停播放器
        super.pause()
        videoPlayer.pause()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        videoPlayer.setVideoSurfaceView(surfaceView)
    }
}