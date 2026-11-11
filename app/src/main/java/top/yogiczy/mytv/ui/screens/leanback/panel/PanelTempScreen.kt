package top.yogiczy.mytv.ui.screens.leanback.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.progress
import top.yogiczy.mytv.data.entities.EpgProgrammeCurrent
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelChannelNo
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelIptvInfo
import top.yogiczy.mytv.ui.theme.LeanbackTheme

@Composable
fun LeanbackPanelTempScreen(
    modifier: Modifier = Modifier,
    channelNoProvider: () -> Int = { 0 },
    currentIptvProvider: () -> Iptv = { Iptv() },
    currentIptvUrlIdxProvider: () -> Int = { 0 },
    currentProgrammesProvider: () -> EpgProgrammeCurrent? = { null },
    showProgrammeProgressProvider: () -> Boolean = { false },
) {
    val childPadding = rememberLeanbackChildPadding()

    // 当前节目只算一次记下来：原来同一轮组合要算两遍——频道信息
    // 里拿“正在播/稍后播”算一遍，进度条拿当前节目进度又算一遍；
    // 每遍都要按频道名查表、再把当天节目单扫一遍找正在播的那条，
    // 还各取一次当前时间；同一轮组合里两遍结果必然相同，记下来
    // 两处共用，还避免两遍取时间正好跨过节目边界、信息和进度条
    // 对不上（一个说在播 A、进度条却是 B 的进度）
    val currentProgrammes = currentProgrammesProvider()

    Box(modifier = modifier.fillMaxSize()) {
        LeanbackPanelChannelNo(
            channelNoProvider = { channelNoProvider().toString().padStart(2, '0') },
            modifier = Modifier
                .padding(top = childPadding.top, end = childPadding.end)
                .align(Alignment.TopEnd),
        )

        Layout(
            content = {
                LeanbackPanelIptvInfo(
                    modifier = Modifier
                        .layoutId("info")
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .sizeIn(maxWidth = 400.dp),
                    iptvProvider = currentIptvProvider,
                    iptvUrlIdxProvider = currentIptvUrlIdxProvider,
                    currentProgrammesProvider = { currentProgrammes },
                )

                if (showProgrammeProgressProvider() && currentProgrammes?.now != null) {
                    Box(
                        modifier = Modifier
                            .layoutId("progress")
                            .fillMaxWidth(currentProgrammes.now.progress())
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)),
                    )
                }
            },
            modifier = Modifier
                .padding(
                    start = childPadding.start, bottom = childPadding.bottom
                )
                .align(Alignment.BottomStart)
                .background(
                    MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    MaterialTheme.shapes.medium,
                )
                .clip(MaterialTheme.shapes.medium),
        ) { measurables, constraints ->
            val infoPlaceable = measurables.find { it.layoutId == "info" }?.measure(constraints)
            val progressPlaceable = measurables.find { it.layoutId == "progress" }
                ?.measure(Constraints(maxWidth = infoPlaceable?.width ?: 0))

            layout(infoPlaceable?.width ?: 0, infoPlaceable?.height ?: 0) {
                infoPlaceable?.placeRelative(0, 0)
                progressPlaceable?.placeRelative(
                    0,
                    (infoPlaceable?.height ?: 0) - progressPlaceable.height,
                )
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelTempScreenPreview() {
    LeanbackTheme {
        LeanbackPanelTempScreen(
            channelNoProvider = { 1 },
            currentIptvProvider = { Iptv.EXAMPLE },
            currentProgrammesProvider = {
                EpgProgrammeCurrent(
                    now = EpgProgramme(
                        startAt = System.currentTimeMillis() - 100000,
                        endAt = System.currentTimeMillis() + 200000,
                        title = "实况录像-2023/"
                    ),
                    next = null,
                )
            },
            showProgrammeProgressProvider = { true },
        )
    }
}