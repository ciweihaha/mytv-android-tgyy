package top.yogiczy.mytv.ui.screens.leanback.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.theme.LeanbackTheme

@Composable
fun LeanbackMonitorScreen(
    modifier: Modifier = Modifier,
) {
    val childPadding = rememberLeanbackChildPadding()
    val fpsState = rememberFpsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = childPadding.start, top = childPadding.top),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(
                    color = androidx.tv.material3.MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    shape = androidx.tv.material3.MaterialTheme.shapes.extraSmall,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            LeanbackMonitorFps(fpsProvider = { fpsState.current })
            Spacer(modifier = Modifier.height(4.dp))
            LeanbackMonitorFpsBar(fpsListProvider = { fpsState.history })
        }
    }
}

data class FpsState(
    val current: Int = 0,
    val history: ImmutableList<Int> = persistentListOf(),
)

@Composable
private fun rememberFpsState(): FpsState {
    var state by remember { mutableStateOf(FpsState()) }

    var fpsCount by remember { mutableIntStateOf(0) }
    var lastUpdate by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { ms ->
                // 首帧只记录时间戳：lastUpdate 初始为 0 时 5000 / (ms - 0)
                // 会把开机时长当成分母，算出 0 FPS
                if (lastUpdate == 0L) {
                    lastUpdate = ms
                } else {
                    fpsCount++
                    if (fpsCount == 5) {
                        val elapsed = ms - lastUpdate
                        if (elapsed > 0) {
                            // 浮点除再四舍五入：原先整数除法只往下截，5 帧用 84 毫秒
                            // 实际是 59.5 帧/秒却显示 59，平时也总比真实值低半帧左右
                            val fps = (5_000f / elapsed).roundToInt()
                            state = state.copy(
                                current = fps,
                                history = (state.history.takeLast(30) + fps).toImmutableList(),
                            )
                        }
                        lastUpdate = ms
                        fpsCount = 0
                    }
                }
            }
        }
    }

    return state
}

@Composable
private fun LeanbackMonitorFps(
    modifier: Modifier = Modifier,
    fpsProvider: () -> Int = { 0 },
) {
    val fps = fpsProvider()

    Text(
        modifier = modifier,
        text = "FPS: $fps",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Preview
@Composable
private fun LeanbackMonitorFpsPreview() {
    LeanbackTheme {
        LeanbackMonitorFps(
            fpsProvider = { 60 }
        )
    }
}

/** FPS 柱状图一次性算好的尺寸：柱宽、柱间距、最大高度（单位 px） */
private data class FpsBarGeometry(
    val barWidth: Float,
    val barSpacing: Float,
    val maxBarHeight: Float,
)

@Composable
private fun LeanbackMonitorFpsBar(
    modifier: Modifier = Modifier,
    fpsListProvider: () -> ImmutableList<Int> = { persistentListOf() },
) {
    val fpsList = fpsListProvider()

    // 画布固定 140x40dp：柱宽、间距、最大高度只随屏幕密度算一次记下来，
    // 每次画图直接用，不再每次画都现查密度、现除一次
    val density = LocalDensity.current
    val barGeometry = remember(density) {
        with(density) {
            FpsBarGeometry(
                barWidth = 140.dp.toPx() / 60,
                barSpacing = 2.dp.toPx(),
                maxBarHeight = 40.dp.toPx(),
            )
        }
    }

    Canvas(
        modifier = modifier
            .width(140.dp)
            .height(40.dp),
    ) {
        val (barWidth, barSpacing, maxBarHeight) = barGeometry

        for (i in fpsList.indices) {
            val fps = fpsList[i]

            val barHeight =
                (fps.coerceAtMost(60) * maxBarHeight / 60) // 柱状条的高度，60 帧时顶满最大高度

            val x = i * (barWidth + barSpacing)
            val y = size.height - barHeight
            val rect = Rect(x, y, x + barWidth, size.height)

            val color = when (fps) {
                in 0..30 -> Color(0xfff44336)
                in 31..45 -> Color(0xffffeb3b)
                else -> Color(0xff00a2ff)
            }

            drawRect(color, topLeft = rect.topLeft, size = rect.size)
        }
    }
}

@Preview
@Composable
private fun LeanbackMonitorFpsBarPreview() {
    LeanbackTheme {
        LeanbackMonitorFpsBar(
            fpsListProvider = {
                List(30) { it * 2 }.toImmutableList()
            }
        )
    }
}

@Preview
@Composable
private fun LeanbackMonitorScreenPreview() {
    LeanbackTheme {
        LeanbackMonitorScreen()
    }
}