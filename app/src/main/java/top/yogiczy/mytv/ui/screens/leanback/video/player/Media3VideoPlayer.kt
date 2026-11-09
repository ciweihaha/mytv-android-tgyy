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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import top.yogiczy.mytv.ui.utils.SP
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
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
        if (cachedDataSourceFactory == null || cachedDataSourceFactoryKey != key) {
            cachedDataSourceFactoryKey = key
            cachedDataSourceFactory =
                DefaultDataSource.Factory(context, createHttpDataSourceFactory())
        }
        return cachedDataSourceFactory!!
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
        return OkHttpDataSource.Factory(okHttpClient).apply {
            setUserAgent(SP.videoPlayerUserAgent)
        }
    }

    @OptIn(UnstableApi::class)
    private fun prepare(uri: Uri, contentType: Int? = null) {
        val dataSourceFactory = getDataSourceFactory()

        val mediaItem = MediaItem.fromUri(uri)

        val mediaSource = when (val type = contentType ?: Util.inferContentType(uri)) {
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
                        errorCodeName = "${PlaybackException.UNSUPPORTED_TYPE.message}_$type"
                    )
                )
                null
            }
        }

        if (mediaSource != null) {
            contentTypeAttempts[contentType ?: Util.inferContentType(uri)] = true
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
                if (uri != null) {
                    if (contentTypeAttempts[C.CONTENT_TYPE_HLS] != true) {
                        prepare(uri, C.CONTENT_TYPE_HLS)
                    } else if (contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true) {
                        prepare(uri, C.CONTENT_TYPE_OTHER)
                    } else if (contentTypeAttempts[C.CONTENT_TYPE_RTSP] != true) {
                        prepare(uri, C.CONTENT_TYPE_RTSP)
                    } else {
                        triggerError(PlaybackException.UNSUPPORTED_TYPE)
                    }
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
                        delay(1000)
                    }
                }
            }

            if (playbackState != Player.STATE_BUFFERING) {
                triggerBuffering(false)
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
                videoWidth = format.width,
                videoHeight = format.height,
                videoColor = format.colorInfo?.toLogString() ?: "",
                // TODO 帧率、比特率目前是从tag中获取，有的返回空，后续需要实时计算
                videoFrameRate = format.frameRate,
                videoBitrate = format.bitrate,
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
                audioChannels = format.channelCount,
                audioSampleRate = format.sampleRate,
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
        videoPlayer.pause()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        videoPlayer.setVideoSurfaceView(surfaceView)
    }
}