package top.yogiczy.mytv.ui.screens.leanback.panel.components

import android.net.TrafficStats
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import java.text.DecimalFormat

@Composable
fun LeanbackPanelPlayerInfo(
    modifier: Modifier = Modifier,
    metadataProvider: () -> LeanbackVideoPlayer.Metadata = { LeanbackVideoPlayer.Metadata() },
) {
    CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.bodyLarge,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelPlayerInfoResolution(
                resolutionProvider = {
                    val metadata = metadataProvider()
                    metadata.videoWidth to metadata.videoHeight
                }
            )

            PanelPlayerInfoNetSpeed()
        }
    }
}

@Composable
private fun PanelPlayerInfoResolution(
    modifier: Modifier = Modifier,
    resolutionProvider: () -> Pair<Int, Int> = { 0 to 0 },
) {
    val resolution = resolutionProvider()

    Text(
        text = "分辨率：${resolution.first}×${resolution.second}",
        modifier = modifier,
    )
}

@Composable
private fun PanelPlayerInfoNetSpeed(
    modifier: Modifier = Modifier,
    netSpeed: Long = rememberNetSpeed(),
) {
    // 网速格式器记住复用：面板开着时网速每秒变一次、每次重组都新建一个
    // DecimalFormat 是纯浪费；只在主线程重组里用，无线程安全问题
    val speedFormat = remember { DecimalFormat("#.#") }
    // KB 这一档原来是整数除法：157 轮把算式修对后，网速不到 1KB/s
    // 时算出来是几百没错，但这里一除又截回 0，屏幕上还是 0KB/s；
    // 和 MB 那档一样改成浮点除、留一位小数，不到 1KB/s 显示 0.5KB/s
    Text(
        text = if (netSpeed < 1024 * 999) "网速：${speedFormat.format(netSpeed / 1024f)}KB/s"
        else "网速：${speedFormat.format(netSpeed / 1024 / 1024f)}MB/s",
        modifier = modifier,
    )
}

@Composable
private fun rememberNetSpeed(): Long {
    var netSpeed by remember { mutableLongStateOf(0) }

    // 只在前台才每秒采样：面板开着按 Home 退后台后组合还在（没人
    // 会在退后台时关面板），原先的 LaunchedEffect(Unit) 会一直每秒
    // 读一次流量、白白唤醒主线程；回到前台时重新取基线再采样，
    // 避免把后台那段时间算进一次网速里
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastTotalRxBytes = TrafficStats.getTotalRxBytes()
            var lastTimeStamp = System.currentTimeMillis()

            while (true) {
                delay(1000)
                val nowTotalRxBytes = TrafficStats.getTotalRxBytes()
                val nowTimeStamp = System.currentTimeMillis()
                // 系统时间被往回调时两时刻可能相同甚至倒挂：除零会崩溃，
                // 负数会算出负网速——只在时间正常前进时更新
                // 先乘后除：两边都是 Long，先除会把每毫秒的零头截掉再乘 1000，
                // 一秒流量不到 1KB 时直接算成 0，平时也固定少算将近 1KB/s；
                // 先乘 1000 再除，结果一样但零头保留；一秒流量不过几 MB，
                // 乘 1000 远到不了 Long 上限，不会溢出
                val elapsed = nowTimeStamp - lastTimeStamp
                if (elapsed > 0) {
                    netSpeed = (nowTotalRxBytes - lastTotalRxBytes) * 1000 / elapsed
                }
                lastTimeStamp = nowTimeStamp
                lastTotalRxBytes = nowTotalRxBytes
            }
        }
    }

    return netSpeed
}

@Preview
@Composable
private fun LeanbackPanelPlayerInfoPreview() {
    LeanbackTheme {
        LeanbackPanelPlayerInfo(
            metadataProvider = {
                LeanbackVideoPlayer.Metadata(
                    videoWidth = 1920,
                    videoHeight = 1080,
                )
            },
        )
    }
}

@Preview
@Composable
private fun LeanbackPanelPlayerInfoNetSpeedPreview() {
    LeanbackTheme {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelPlayerInfoNetSpeed()
            PanelPlayerInfoNetSpeed(netSpeed = 54321)
            PanelPlayerInfoNetSpeed(netSpeed = 1222 * 1222)
        }
    }
}