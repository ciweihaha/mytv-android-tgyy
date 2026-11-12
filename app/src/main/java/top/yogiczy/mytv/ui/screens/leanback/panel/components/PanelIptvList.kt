package top.yogiczy.mytv.ui.screens.leanback.panel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.utils.initialScrollIndex

@Composable
fun LeanbackPanelIptvList(
    modifier: Modifier = Modifier,
    iptvListProvider: () -> IptvList = { IptvList() },
    epgListProvider: () -> EpgList = { EpgList() },
    currentIptvProvider: () -> Iptv = { Iptv() },
    showProgrammeProgressProvider: () -> Boolean = { false },
    onIptvSelected: (Iptv) -> Unit = {},
    onIptvFavoriteToggle: (Iptv) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val iptvList = iptvListProvider()
    val epgList = epgListProvider()

    // 频道名 → 节目单走数据层那份共用的查找（先按名、再按 tvg-id 兜底）：
    // 原来这里直接拿按名建好的表按名查，名字写法和节目单不一样的台
    // （直播源“CCTV综合”、节目单“CCTV-1 综合”）查不到节目；现在
    // 经 epgFor 查，名字对不上时按 tvg-id 找回节目单，名字对得上的
    // 台结果和原来一模一样

    // 初始滚动位置只算一次记下来：原来每次组合都按当前频道把频道列表线性扫一遍找下标，
    // 算完只在第一次建列表状态时用一次（rememberLazyListState 之后不再看这个参数），
    // 焦点一动、父界面一刷新就白白重扫一次；只在频道列表变了时重算。
    // 普通面板的分组列表和经典面板的分组列表早就是这么做的，这里是普通面板里
    // 还没收的同类写法
    val initialIptvIdx = remember(iptvList) {
        initialScrollIndex(iptvList.indexOf(currentIptvProvider()))
    }
    val listState = rememberLazyListState(initialIptvIdx)
    val childPadding = rememberLeanbackChildPadding()

    var hasFocused by rememberSaveable { mutableStateOf(false) }

    var showEpgDialog by remember { mutableStateOf(false) }
    var currentShowEpgIptv by remember { mutableStateOf(Iptv()) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(
            start = childPadding.start,
            end = childPadding.end,
        ),
    ) {
        // 按频道名（String）设 key：取消收藏等操作让列表缩短时，焦点与
        // 记住的状态跟着频道走，而不是留在原位置错位到另一频道上。
        // 不能用频道对象本身作 key：Lazy 列表保存状态时要把 key 存进
        // Bundle，Iptv 不是 Parcelable/Serializable，会抛
        // IllegalArgumentException 直接闪退（见 crash.txt 第 2543 行）。
        // 同组内频道名唯一（buildGroupList 按名合并），用名作 key 安全。
        items(iptvList, key = { it.name }) { iptv ->
            LeanbackPanelIptvItem(
                iptvProvider = { iptv },
                currentProgrammeProvider = {
                    epgList.epgFor(iptv)?.currentProgrammes()?.now
                },
                showProgrammeProgressProvider = { showProgrammeProgressProvider() },
                onIptvSelected = { onIptvSelected(iptv) },
                onIptvFavoriteToggle = { onIptvFavoriteToggle(iptv) },
                onShowEpg = {
                    currentShowEpgIptv = iptv
                    showEpgDialog = true
                },
                initialFocusedProvider = { iptv == currentIptvProvider() && !hasFocused },
                onHasFocused = { hasFocused = true },
            )
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

@Preview
@Composable
private fun LeanbackPanelIptvListPreview() {
    LeanbackTheme {
        LeanbackPanelIptvList(
            modifier = Modifier.padding(20.dp),
            iptvListProvider = { IptvList.EXAMPLE },
            currentIptvProvider = { Iptv.EXAMPLE },
        )
    }
}