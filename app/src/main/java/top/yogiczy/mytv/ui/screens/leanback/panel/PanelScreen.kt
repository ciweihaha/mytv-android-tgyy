package top.yogiczy.mytv.ui.screens.leanback.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.channelNoText
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.favoriteIptvList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelChannelNo
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelDateTime
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelIptvFavoriteList
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelIptvGroupList
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelIptvInfo
import top.yogiczy.mytv.ui.screens.leanback.panel.components.LeanbackPanelPlayerInfo
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackUserAction

/**
 * 选台面板底部列表的三种状态：0 全部（按分组）、1 收藏、2 最近观看。
 * 遥控器按“上”在分组列表顶端时进特殊列表（收藏优先、没有收藏时进
 * 最近观看、两个都没有时留在全部）；特殊列表里再按“上”循环到下一个
 * （收藏→最近观看→全部），不用多占一个键。顶层函数方便单元测试直接调用。
 */
internal fun nextSpecialListMode(currentMode: Int, hasFavorites: Boolean, hasRecents: Boolean): Int =
    when (currentMode) {
        1 -> if (hasRecents) 2 else 0
        2 -> 0
        else -> if (hasFavorites) 1 else if (hasRecents) 2 else 0
    }

@Composable
fun LeanbackPanelScreen(
    modifier: Modifier = Modifier,
    iptvGroupListProvider: () -> IptvGroupList = { IptvGroupList() },
    epgListProvider: () -> EpgList = { EpgList() },
    currentIptvProvider: () -> Iptv = { Iptv() },
    currentIptvUrlIdxProvider: () -> Int = { 0 },
    videoPlayerMetadataProvider: () -> LeanbackVideoPlayer.Metadata = { LeanbackVideoPlayer.Metadata() },
    showProgrammeProgressProvider: () -> Boolean = { false },
    iptvFavoriteEnableProvider: () -> Boolean = { true },
    iptvFavoriteListProvider: () -> ImmutableList<String> = { persistentListOf() },
    iptvRecentListProvider: () -> IptvList = { IptvList() },
    iptvFavoriteListVisibleProvider: () -> Boolean = { false },
    onIptvFavoriteListVisibleChange: (Boolean) -> Unit = {},
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onClose: () -> Unit = {},
    autoCloseState: PanelAutoCloseState = rememberPanelAutoCloseState(
        timeout = Constants.UI_SCREEN_AUTO_CLOSE_DELAY,
        onTimeout = onClose,
    ),
) {
    LaunchedEffect(Unit) {
        autoCloseState.active()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            // 任意操作都续期自动关闭计时：原来只在列表滚动时续期，不滚动
            // 的按键浏览（短分组、节目单弹窗里只看不动）15 秒就自动关掉；
            // 快捷面板已经是这个写法（先续期、再点按关闭），照它抄
            .handleLeanbackUserAction { autoCloseState.active() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    ) {
        LeanbackPanelScreenTopRight(
            channelNoProvider = {
                iptvGroupListProvider().channelNoText(currentIptvProvider())
            }
        )

        LeanbackPanelScreenBottom(
            iptvGroupListProvider = iptvGroupListProvider,
            epgListProvider = epgListProvider,
            currentIptvProvider = currentIptvProvider,
            currentIptvUrlIdxProvider = currentIptvUrlIdxProvider,
            videoPlayerMetadataProvider = videoPlayerMetadataProvider,
            showProgrammeProgressProvider = showProgrammeProgressProvider,
            iptvFavoriteEnableProvider = iptvFavoriteEnableProvider,
            iptvFavoriteListProvider = iptvFavoriteListProvider,
            iptvFavoriteListVisibleProvider = iptvFavoriteListVisibleProvider,
            onIptvFavoriteListVisibleChange = onIptvFavoriteListVisibleChange,
            onIptvSelected = onIptvSelected,
            onIptvFavoriteToggle = onIptvFavoriteToggle,
            onUserAction = { autoCloseState.active() },
        )
    }
}

@Composable
fun LeanbackPanelScreenTopRight(
    modifier: Modifier = Modifier,
    channelNoProvider: () -> String = { "" },
) {
    val childPadding = rememberLeanbackChildPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = childPadding.top, end = childPadding.end),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeanbackPanelChannelNo(channelNoProvider = channelNoProvider)

            Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                Spacer(
                    modifier = Modifier
                        .background(Color.White)
                        .width(2.dp)
                        .height(30.dp),
                )
            }

            LeanbackPanelDateTime()
        }
    }
}

@Composable
private fun LeanbackPanelScreenBottom(
    modifier: Modifier = Modifier,
    iptvGroupListProvider: () -> IptvGroupList = { IptvGroupList() },
    epgListProvider: () -> EpgList = { EpgList() },
    currentIptvProvider: () -> Iptv = { Iptv() },
    currentIptvUrlIdxProvider: () -> Int = { 0 },
    videoPlayerMetadataProvider: () -> LeanbackVideoPlayer.Metadata = { LeanbackVideoPlayer.Metadata() },
    showProgrammeProgressProvider: () -> Boolean = { false },
    iptvFavoriteEnableProvider: () -> Boolean = { true },
    iptvFavoriteListProvider: () -> ImmutableList<String> = { persistentListOf() },
    iptvFavoriteListVisibleProvider: () -> Boolean = { false },
    onIptvFavoriteListVisibleChange: (Boolean) -> Unit = {},
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val childPadding = rememberLeanbackChildPadding()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LeanbackPanelIptvInfo(
                modifier = Modifier
                    .padding(start = childPadding.start),
                iptvProvider = currentIptvProvider,
                iptvUrlIdxProvider = currentIptvUrlIdxProvider,
                currentProgrammesProvider = {
                    epgListProvider().currentProgrammes(currentIptvProvider())
                }
            )

            LeanbackPanelPlayerInfo(
                modifier = Modifier.padding(start = childPadding.start),
                metadataProvider = videoPlayerMetadataProvider
            )

            LeanbackPanelScreenBottomIptvList(
                iptvGroupListProvider = iptvGroupListProvider,
                epgListProvider = epgListProvider,
                currentIptvProvider = currentIptvProvider,
                showProgrammeProgressProvider = showProgrammeProgressProvider,
                iptvFavoriteEnableProvider = iptvFavoriteEnableProvider,
                iptvFavoriteListProvider = iptvFavoriteListProvider,
                iptvRecentListProvider = iptvRecentListProvider,
                iptvFavoriteListVisibleProvider = iptvFavoriteListVisibleProvider,
                onIptvFavoriteListVisibleChange = onIptvFavoriteListVisibleChange,
                onIptvSelected = onIptvSelected,
                onIptvFavoriteToggle = onIptvFavoriteToggle,
                onUserAction = onUserAction,
            )
        }
    }
}

@Composable
fun LeanbackPanelScreenBottomIptvList(
    modifier: Modifier = Modifier,
    iptvGroupListProvider: () -> IptvGroupList = { IptvGroupList() },
    epgListProvider: () -> EpgList = { EpgList() },
    currentIptvProvider: () -> Iptv = { Iptv() },
    showProgrammeProgressProvider: () -> Boolean = { false },
    iptvFavoriteEnableProvider: () -> Boolean = { true },
    iptvFavoriteListProvider: () -> ImmutableList<String> = { persistentListOf() },
    iptvRecentListProvider: () -> IptvList = { IptvList() },
    iptvFavoriteListVisibleProvider: () -> Boolean = { false },
    onIptvFavoriteListVisibleChange: (Boolean) -> Unit = {},
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val iptvFavoriteEnable = iptvFavoriteEnableProvider()
    // 底部列表三种状态（见 nextSpecialListMode）：0 全部、1 收藏、2 最近观看；
    // 设置里记的“收藏列表可见”只对应开局进收藏（1），其余状态都按 0 开局
    var listMode by remember { mutableStateOf(if (iptvFavoriteListVisibleProvider()) 1 else 0) }

    fun setListMode(mode: Int) {
        listMode = mode
        onIptvFavoriteListVisibleChange(mode != 0)
    }

    // 收藏过滤走数据层那份共用的查找：原来普通面板和经典面板各写了一份
    // 一模一样的（收藏名单只取一次转成集合、全量频道按名筛），改一处
    // 容易漏另一处；现在两处都调这一份。每次调都现取名单和频道列表，
    // 收藏增减了立即生效，结果和原来一模一样
    val favoriteIptvList: () -> IptvList = {
        iptvGroupListProvider().favoriteIptvList(iptvFavoriteListProvider())
    }
    val recentIptvList: () -> IptvList = { iptvRecentListProvider() }

    Box(modifier = modifier.height(150.dp)) {
        if (listMode == 1)
            // 收藏与最近观看共用同一个网格列表组件（只是标题与数据源不同）：
            // 两处各写一个调用点、不用同一个调用点换 provider——列表组件
            // 内部用 remember(key) 只记第一次取到的列表，同一个调用点换
            // 数据源不会重取，切过去显示的还是旧列表
            LeanbackPanelIptvFavoriteList(
                iptvListProvider = { favoriteIptvList() },
                epgListProvider = epgListProvider,
                currentIptvProvider = currentIptvProvider,
                showProgrammeProgressProvider = showProgrammeProgressProvider,
                onIptvSelected = onIptvSelected,
                onIptvFavoriteToggle = onIptvFavoriteToggle,
                onClose = {
                    // 特殊列表里按“上”循环到下一个：收藏→最近观看→全部；
                    // 取消收藏到空时也走这里，有最近观看就落到最近观看上
                    setListMode(
                        nextSpecialListMode(
                            1,
                            favoriteIptvList().isNotEmpty(),
                            recentIptvList().isNotEmpty(),
                        )
                    )
                },
                onUserAction = onUserAction,
            )
        else if (listMode == 2)
            LeanbackPanelIptvFavoriteList(
                title = "最近观看",
                iptvListProvider = { recentIptvList() },
                epgListProvider = epgListProvider,
                currentIptvProvider = currentIptvProvider,
                showProgrammeProgressProvider = showProgrammeProgressProvider,
                onIptvSelected = onIptvSelected,
                onIptvFavoriteToggle = onIptvFavoriteToggle,
                onClose = { setListMode(0) },
                onUserAction = onUserAction,
            )
        else
            LeanbackPanelIptvGroupList(
                iptvGroupListProvider = iptvGroupListProvider,
                epgListProvider = epgListProvider,
                currentIptvProvider = currentIptvProvider,
                showProgrammeProgressProvider = showProgrammeProgressProvider,
                onIptvSelected = onIptvSelected,
                onIptvFavoriteToggle = onIptvFavoriteToggle,
                onToFavorite = {
                    // 分组列表顶端按“上”进特殊列表：收藏开关没开时不进收藏，
                    // 但最近观看不受收藏开关管制；两个都没有时弹提示、留在全部
                    val nextMode = nextSpecialListMode(
                        0,
                        iptvFavoriteEnable && favoriteIptvList().isNotEmpty(),
                        recentIptvList().isNotEmpty(),
                    )
                    if (nextMode == 0) {
                        LeanbackToastState.I.showToast("没有收藏或最近观看的频道")
                    } else {
                        setListMode(nextMode)
                    }
                },
                onUserAction = onUserAction,
            )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelScreenTopRightPreview() {
    LeanbackTheme {
        LeanbackPanelScreenTopRight(
            channelNoProvider = { "01" },
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelScreenBottomPreview() {
    LeanbackTheme {
        LeanbackPanelScreenBottom(
            currentIptvProvider = { Iptv.EXAMPLE },
            currentIptvUrlIdxProvider = { 0 },
            iptvGroupListProvider = { IptvGroupList.EXAMPLE },
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelScreenPreview() {
    LeanbackTheme {
        LeanbackPanelScreen(
            currentIptvProvider = { Iptv.EXAMPLE },
            currentIptvUrlIdxProvider = { 0 },
            iptvGroupListProvider = { IptvGroupList.EXAMPLE },
        )
    }
}