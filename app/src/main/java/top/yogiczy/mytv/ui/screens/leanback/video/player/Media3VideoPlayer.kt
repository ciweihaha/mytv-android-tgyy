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

    companion object {
        const val MAX_BEHIND_LIVE_WINDOW_RETRIES = 3
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
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(TrustAllCertsManager), SecureRandom())
        }
        val okHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, TrustAllCertsManager)
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
            // 如果是直播加载位置错误，先重试几次；一直落后说明该线路的
            // 列表已过期不再更新，继续重试只是无限缓冲，交给上层换线
            if (ex.errorCode == Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                if (++behindLiveWindowRetries > MAX_BEHIND_LIVE_WINDOW_RETRIES) {
                    behindLiveWindowRetries = 0
                    triggerError(
                        PlaybackException(ex.errorCodeName, ex.errorCode)
                    )
                } else {
                    videoPlayer.seekToDefaultPosition()
                    videoPlayer.prepare()
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
                // 播起来了：位置落后计数清零，下次再落后重新数
                behindLiveWindowRetries = 0
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
        videoPlayer.addAnalyticsListener(eventLogger)
        videoPlayer.setVideoFrameMetadataListener { _, _, _, _ ->
            renderedFrameCount.incrementAndGet()
        }
        // 不用清监听器：setVideoFrameMetadataListener 只接受非空，
        // 且 release() 里 videoPlayer.release() 后它再也不会回调
    }

    override fun release() {
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