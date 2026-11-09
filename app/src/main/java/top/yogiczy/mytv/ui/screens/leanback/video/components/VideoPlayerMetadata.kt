package top.yogiczy.mytv.ui.screens.leanback.video.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer
import top.yogiczy.mytv.ui.theme.LeanbackTheme

/**
 * 0（或空串）即未知：第 76 轮把 Media3 的 NO_VALUE（-1）归一成了
 * 0，直接显示会把“未知”读成实测的 0（“帧率: 0.0”“比特率: 0 kbps”）
 */
private fun Float.orUnknown(): String = if (this == 0f) "未知" else toString()
private fun Int.orUnknown(): String = if (this == 0) "未知" else toString()
private fun String.orUnknown(): String = if (isBlank()) "未知" else this

@Composable
fun LeanbackVideoPlayerMetadata(
    modifier: Modifier = Modifier,
    metadata: LeanbackVideoPlayer.Metadata,
) {
    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.labelMedium,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                    MaterialTheme.shapes.extraSmall,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column {
                Text("视频", style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text("编码: ${metadata.videoMimeType.orUnknown()}")
                    Text("解码器: ${metadata.videoDecoder.orUnknown()}")
                    Text(
                        "分辨率: ${
                            if (metadata.videoWidth == 0 || metadata.videoHeight == 0) "未知"
                            else "${metadata.videoWidth}x${metadata.videoHeight}"
                        }"
                    )
                    Text("色彩: ${metadata.videoColor.orUnknown()}")
                    Text("帧率: ${metadata.videoFrameRate.orUnknown()}")
                    Text(
                        "比特率: ${
                            if (metadata.videoBitrate == 0) "未知"
                            else "${metadata.videoBitrate / 1024} kbps"
                        }"
                    )
                }
            }

            Column {
                Text("音频", style = MaterialTheme.typography.bodyMedium)
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text("编码: ${metadata.audioMimeType.orUnknown()}")
                    Text("解码器: ${metadata.audioDecoder.orUnknown()}")
                    Text("声道数: ${metadata.audioChannels.orUnknown()}")
                    Text(
                        "采样率: ${
                            if (metadata.audioSampleRate == 0) "未知"
                            else "${metadata.audioSampleRate} Hz"
                        }"
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun LeanbackVideoMetadataPreview() {
    LeanbackTheme {
        LeanbackVideoPlayerMetadata(
            metadata = LeanbackVideoPlayer.Metadata(
                videoWidth = 1920,
                videoHeight = 1080,
                videoMimeType = "video/hevc",
                videoColor = "BT2020/Limited range/HLG/8/8",
                videoFrameRate = 25.0f,
                videoBitrate = 10605096,
                videoDecoder = "c2.goldfish.h264.decoder",

                audioMimeType = "audio/mp4a-latm",
                audioChannels = 2,
                audioSampleRate = 32000,
                audioDecoder = "c2.android.aac.decoder",
            )
        )
    }
}