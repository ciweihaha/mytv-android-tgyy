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
import kotlin.math.max
import kotlin.math.min

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

    // 频道名 → 节目单一次建表记下来：原先每个频道项每次组合都拿频道名
    // 把整个节目单线性扫一遍找对应频道，焦点每挪一下、进度每刷一次就
    // 全量重扫；节目单只在刷新时才变，建一次表、后面按名直接查。
    // 同名频道（两个源都叫一个名）留第一次出现的，与原来 firstOrNull
    // 取到的那条一致
    val epgByChannel = remember(epgList) {
        buildMap<String, Epg> {
            epgList.forEach { epg -> putIfAbsent(epg.channel, epg) }
        }
    }

    val listState = rememberLazyListState(max(0, iptvList.indexOf(currentIptvProvider()) - 2))
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
        // 按频道对象设 key：取消收藏等操作让列表缩短时，焦点与记住的状态
        // 跟着频道走，而不是留在原位置错位到另一频道上
        items(iptvList, key = { it }) { iptv ->
            LeanbackPanelIptvItem(
                iptvProvider = { iptv },
                currentProgrammeProvider = {
                    epgByChannel[iptv.channelName]?.currentProgrammes()?.now
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
            epgByChannel[currentShowEpgIptv.channelName] ?: Epg()
        },
        modifier = Modifier
            .handleLeanbackKeyEvents(
                onLeft = {
                    // 列表为空时下标越界：对话框打开后列表理论上不会变空，但守卫一下
                    if (iptvList.isNotEmpty()) {
                        currentShowEpgIptv = iptvList[max(0, iptvList.indexOf(currentShowEpgIptv) - 1)]
                    }
                },
                onRight = {
                    if (iptvList.isNotEmpty()) {
                        currentShowEpgIptv =
                            iptvList[min(iptvList.size - 1, iptvList.indexOf(currentShowEpgIptv) + 1)]
                    }
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