package top.yogiczy.mytv.ui.screens.leanback.panel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import kotlin.math.max

@Composable
fun LeanbackPanelIptvFavoriteList(
    modifier: Modifier = Modifier,
    iptvListProvider: () -> IptvList = { IptvList() },
    epgListProvider: () -> EpgList = { EpgList() },
    currentIptvProvider: () -> Iptv = { Iptv() },
    showProgrammeProgressProvider: () -> Boolean = { false },
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onClose: () -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val favoriteListSize = 6
    val childPadding = rememberLeanbackChildPadding()

    var key by remember { mutableIntStateOf(0) }
    val iptvList = remember(key) { iptvListProvider() }
    val epgList = epgListProvider()

    // 频道名 → 节目单走数据层那份共用的查找（先按名、再按 tvg-id 兜底）：
    // 原来这里直接拿按名建好的表按名查，名字写法和节目单不一样的台
    // （直播源“CCTV综合”、节目单“CCTV-1 综合”）查不到节目；现在
    // 经 epgFor 查，名字对不上时按 tvg-id 找回节目单，名字对得上的
    // 台结果和原来一模一样

    // 初始滚动位置只算一次记下来：同普通面板频道列表——原来每次组合都按当前频道
    // 把收藏列表线性扫一遍找下标，算完只在第一次建网格状态时用一次，
    // 焦点一动、父界面一刷新就白白重扫一次；只在收藏列表变了时重算
    val initialIptvIdx = remember(iptvList) {
        max(0, iptvList.indexOf(currentIptvProvider()))
    }
    val listState = rememberLazyGridState(initialIptvIdx)

    var hasFocused by rememberSaveable { mutableStateOf(false) }

    var showEpgDialog by remember { mutableStateOf(false) }
    var currentShowEpgIptv by remember { mutableStateOf(Iptv()) }

    LaunchedEffect(iptvList) {
        if (iptvList.isEmpty()) onClose()
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.padding(start = childPadding.start)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.labelMedium,
            ) {
                Text(text = "收藏")
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${iptvList.size}个频道",
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
            }
        }

        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(favoriteListSize),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(
                top = 6.dp,
                start = childPadding.start,
                end = childPadding.end,
                bottom = childPadding.bottom,
            ),
        ) {
            // 按频道对象设 key：取消收藏让列表缩短时，焦点与记住的状态
            // 跟着频道走；同名频道可能来自不同分组，不能只用 channelName
            itemsIndexed(iptvList, key = { _, iptv -> iptv }) { index, iptv ->
                LeanbackPanelIptvItem(
                    modifier = if (index < favoriteListSize) {
                        Modifier.handleLeanbackKeyEvents(onUp = { onClose() })
                    } else Modifier,
                    iptvProvider = { iptv },
                    currentProgrammeProvider = {
                        epgList.epgFor(iptv)?.currentProgrammes()?.now
                    },
                    showProgrammeProgressProvider = { showProgrammeProgressProvider() },
                    onIptvSelected = { onIptvSelected(iptv) },
                    onIptvFavoriteToggle = {
                        key++
                        onIptvFavoriteToggle(iptv)
                    },
                    onShowEpg = {
                        currentShowEpgIptv = iptv
                        showEpgDialog = true
                    },
                    initialFocusedProvider = {
                        if (hasFocused) false
                        else if (index == 0 && !iptvList.contains(currentIptvProvider())) true
                        else iptv == currentIptvProvider()
                    },
                    onHasFocused = { hasFocused = true },
                )
            }
        }
    }

    LeanbackPanelIptvEpgDialog(
        showDialogProvider = { showEpgDialog },
        onDismissRequest = { showEpgDialog = false },
        iptvProvider = { currentShowEpgIptv },
        epgProvider = {
            epgList.epgFor(currentShowEpgIptv) ?: Epg()
        },
        modifier = Modifier
            .handleLeanbackKeyEvents(
                onLeft = {
                    currentShowEpgIptv = iptvList.epgNeighbor(currentShowEpgIptv, -1)
                },
                onRight = {
                    currentShowEpgIptv = iptvList.epgNeighbor(currentShowEpgIptv, +1)
                },
            ),
        onUserAction = onUserAction,
    )
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelIptvFavoriteListPreview() {
    LeanbackTheme {
        LeanbackPanelIptvFavoriteList(
            iptvListProvider = { IptvList.EXAMPLE },
        )
    }
}