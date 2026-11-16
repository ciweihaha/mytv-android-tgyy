package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Media3 Format 转界面元数据：视频/音频两路转换原来是 metadataListener
 * 回调里内联的 copy，单测够不着，2126 轮抽到顶层后直接断言（壳 906 轮
 * 姊妹检查）。
 * 注意：天光这边未知值归一到 0（不是 null），与未收到元数据时一致；
 * 没 colorInfo 时颜色写空串。
 */
class Media3FormatMetadataTest {
    @Test
    fun `视频元数据完整值原样转`() {
        val format = Format.Builder()
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(25f)
            .setAverageBitrate(8_000_000)
            .setSampleMimeType("video/hevc")
            .build()
        val video = format.toVideoMetadata()
        assertEquals(1920, video.videoWidth)
        assertEquals(1080, video.videoHeight)
        assertEquals(25f, video.videoFrameRate, 0.001f)
        assertEquals(8_000_000, video.videoBitrate)
        assertEquals("video/hevc", video.videoMimeType)
        assertEquals("", video.videoColor)
    }

    @Test
    fun `视频元数据未知值转零不显示负数`() {
        // 默认 Format 全是 NO_VALUE(-1)：不归零调试信息会显示“-1x-1”
        val video = Format.Builder().build().toVideoMetadata()
        assertEquals(0, video.videoWidth)
        assertEquals(0, video.videoHeight)
        assertEquals(0f, video.videoFrameRate, 0.001f)
        assertEquals(0, video.videoBitrate)
        assertEquals("", video.videoMimeType)
        assertEquals("", video.videoColor)
    }

    @Test
    fun `视频元数据零值转零`() {
        // 宽/高 0 是 Builder 允许的（真有 0 宽高的流会走到这里）；
        // 帧率 0 建不出来（Builder 拒掉），只剩 NO_VALUE 那档，已在
        // “未知值转零”里断言过
        val format = Format.Builder()
            .setWidth(0)
            .setHeight(0)
            .build()
        val video = format.toVideoMetadata()
        assertEquals(0, video.videoWidth)
        assertEquals(0, video.videoHeight)
    }

    @Test
    fun `视频元数据码率取峰值优先于平均值`() {
        // Format.bitrate 的定义：峰值已知取峰值，否则取平均值，否则 NO_VALUE
        val format = Format.Builder()
            .setAverageBitrate(8_000_000)
            .setPeakBitrate(12_000_000)
            .build()
        assertEquals(12_000_000, format.toVideoMetadata().videoBitrate)
    }

    @Test
    fun `视频元数据保留旧的音频与解码器`() {
        val old = LeanbackVideoPlayer.Metadata(
            videoColor = "BT709",
            videoDecoder = "c2.goldfish.h264.decoder",
            audioMimeType = "audio/mp4a-latm",
            audioChannels = 2,
            audioSampleRate = 48_000,
            audioDecoder = "c2.goldfish.aac.decoder",
        )
        val format = Format.Builder()
            .setWidth(1280)
            .setHeight(720)
            .setSampleMimeType("video/avc")
            .build()
        val video = format.toVideoMetadata(old)
        assertEquals("c2.goldfish.h264.decoder", video.videoDecoder)
        assertEquals("audio/mp4a-latm", video.audioMimeType)
        assertEquals(2, video.audioChannels)
        assertEquals(48_000, video.audioSampleRate)
        assertEquals("c2.goldfish.aac.decoder", video.audioDecoder)
        assertEquals(1280, video.videoWidth)
        assertEquals(720, video.videoHeight)
        // 颜色是无条件覆盖的：Format 没带 colorInfo，旧颜色会被写成空串
        assertEquals("", video.videoColor)
    }

    @Test
    fun `音频元数据完整值原样转`() {
        val format = Format.Builder()
            .setChannelCount(6)
            .setSampleRate(48_000)
            .setSampleMimeType("audio/mp4a-latm")
            .build()
        val audio = format.toAudioMetadata()
        assertEquals(6, audio.audioChannels)
        assertEquals(48_000, audio.audioSampleRate)
        assertEquals("audio/mp4a-latm", audio.audioMimeType)
        // 音频这路不碰视频字段
        assertEquals(0, audio.videoWidth)
        assertEquals(0, audio.videoHeight)
    }

    @Test
    fun `音频元数据未知值转零`() {
        val audio = Format.Builder().build().toAudioMetadata()
        assertEquals(0, audio.audioChannels)
        assertEquals(0, audio.audioSampleRate)
        assertEquals("", audio.audioMimeType)
    }

    @Test
    fun `音频元数据零值转零`() {
        // 声道数/采样率 0 是 Builder 允许的（壳 925 轮已对 Media3 源码核过，
        // 只有 setFrameRate 有入参校验）；0 即未知，和 NO_VALUE 那档一样归零
        // 壳 925 姊妹检查：视频零值那档天光早有单测，音频零值一次也没断过
        val format = Format.Builder()
            .setChannelCount(0)
            .setSampleRate(0)
            .build()
        val audio = format.toAudioMetadata()
        assertEquals(0, audio.audioChannels)
        assertEquals(0, audio.audioSampleRate)
        assertEquals("", audio.audioMimeType)
    }

    @Test
    fun `音频元数据保留旧的视频与解码器`() {
        val old = LeanbackVideoPlayer.Metadata(
            videoMimeType = "video/hevc",
            videoWidth = 1920,
            videoHeight = 1080,
            videoDecoder = "c2.goldfish.h264.decoder",
            audioDecoder = "c2.goldfish.aac.decoder",
        )
        val format = Format.Builder()
            .setChannelCount(2)
            .setSampleRate(44_100)
            .setSampleMimeType("audio/mp4a-latm")
            .build()
        val audio = format.toAudioMetadata(old)
        assertEquals("video/hevc", audio.videoMimeType)
        assertEquals(1920, audio.videoWidth)
        assertEquals(1080, audio.videoHeight)
        assertEquals("c2.goldfish.h264.decoder", audio.videoDecoder)
        assertEquals("c2.goldfish.aac.decoder", audio.audioDecoder)
        assertEquals(2, audio.audioChannels)
        assertEquals(44_100, audio.audioSampleRate)
    }
}
