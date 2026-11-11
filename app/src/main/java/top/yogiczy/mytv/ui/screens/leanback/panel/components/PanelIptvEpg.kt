package top.yogiczy.mytv.ui.screens.leanback.panel.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.ListItemDefaults
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

/**
 * 节目单弹窗里左右键切频道：原来普通面板频道列表和收藏列表各写了一份
 * 一模一样的“按频道找下标、加减 1、再夹住不越界”，只差左还是右；
 * 现在抽成一份共用的，两边只传 -1 / +1。行为和原来一字没变：
 * 到头再按左、到尾再按右都停在原地；列表为空时不动；当前频道
 * 不在列表里时（下标 -1），左边 max(0, -2) 取 0、右边 min(末尾, 0)
 * 取 0，都是回到第一个。
 */
internal fun IptvList.epgNeighbor(current: Iptv, delta: Int): Iptv {
    if (isEmpty()) return current
    return this[(indexOf(current) + delta).coerceIn(0, size - 1)]
}

@Composable
fun LeanbackPanelIptvEpgDialog(
    modifier: Modifier = Modifier,
    showDialogProvider: () -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
    iptvProvider: () -> Iptv = { Iptv() },
    epgProvider: () -> Epg = { Epg() },
    onUserAction: () -> Unit = {}
) {
    if (showDialogProvider()) {
        val iptv = iptvProvider()
        val epg = epgProvider()

        AlertDialog(
            modifier = modifier,
            onDismissRequest = onDismissRequest,
            confirmButton = { Text(text = "左右切换频道") },
            title = { Text(iptv.channelName) },
            text = {
                // 每次重组都新建格式器是纯浪费：焦点移动就会重组，记住复用
                val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                var hasFocused by remember(iptv) { mutableStateOf(false) }

                // 列表状态必须 remember：原先每次重组都新建 LazyListState，
                // 滚动位置会被重置回初始下标；只在节目单本身变化时重建
                val listState = remember(epg) {
                    LazyListState(
                        max(0, epg.programmes.indexOfFirst { it.isLive() } - 2)
                    )
                }

                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { _ -> onUserAction() }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    if (epg.programmes.isNotEmpty()) {
                        items(
                            epg.programmes,
                            // 用节目内容作 key：原先用 hashCode()，不同节目可能哈希
                            // 碰撞导致列表复用错行；内容相同的重复节目本就是同一条
                            key = { "${it.startAt}-${it.endAt}-${it.title}" },
                        ) { programme ->
                            var isFocused by remember { mutableStateOf(false) }
                            val focusRequester = remember { FocusRequester() }

                            // “正在播吗”只算一次记下来：原来同一条节目每次组合
                            // 要算三遍——首次聚焦判断、选中状态、播放小图标各
                            // 算一次，每遍都重新取一次当前时间；同一轮组合里
                            // 三遍结果必然相同，记下来三处共用，还避免三遍
                            // 取时间正好跨过节目边界、选中和图标对不上
                            val isLive = programme.isLive()

                            // 时间串只拼一次记下来：原来每次组合都在标题上方现拼一次
                            // “开始 ~ 结束”，焦点一动、每条节目行重组一次就重拼一次；
                            // 开始结束时间不变，拼出来的串也不会变，节目变了才重拼
                            val timeText = remember(programme) {
                                "${timeFormat.format(programme.startAt)}  ~ ${timeFormat.format(programme.endAt)}"
                            }

                            LaunchedEffect(Unit) {
                                if (isLive && !hasFocused) {
                                    hasFocused = true
                                    focusRequester.requestFocus()
                                }
                            }

                            CompositionLocalProvider(
                                LocalContentColor provides if (isFocused) MaterialTheme.colorScheme.background
                                else MaterialTheme.colorScheme.onBackground
                            ) {
                                androidx.tv.material3.ListItem(
                                    modifier = Modifier
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                                        .handleLeanbackKeyEvents(
                                            onSelect = { focusRequester.requestFocus() },
                                        ),
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        focusedContainerColor = MaterialTheme.colorScheme.onBackground,
                                        selectedContainerColor = Color.Transparent,
                                    ),
                                    selected = isLive,
                                    onClick = { },
                                    headlineContent = {
                                        Text(
                                            text = programme.title,
                                            maxLines = if (isFocused) Int.MAX_VALUE else 1,
                                        )
                                    },
                                    overlineContent = {
                                        Text(
                                            text = timeText,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.alpha(0.8f),
                                        )
                                    },
                                    trailingContent = {
                                        if (isLive) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "playing",
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        item {
                            var isFocused by remember { mutableStateOf(false) }
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) {
                                focusRequester.requestFocus()
                            }

                            CompositionLocalProvider(
                                LocalContentColor provides if (isFocused) MaterialTheme.colorScheme.background
                                else MaterialTheme.colorScheme.onBackground
                            ) {
                                androidx.tv.material3.ListItem(
                                    modifier = Modifier
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { isFocused = it.isFocused || it.hasFocus },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent,
                                        focusedContainerColor = MaterialTheme.colorScheme.onBackground,
                                        selectedContainerColor = Color.Transparent,
                                    ),
                                    selected = true,
                                    onClick = { },
                                    headlineContent = {
                                        Text(
                                            text = "当前频道暂无节目",
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackIptvEpgDialogPreview() {
    LeanbackTheme {
        LeanbackPanelIptvEpgDialog(
            showDialogProvider = { true },
            iptvProvider = { Iptv.EXAMPLE },
            epgProvider = {
                Epg(
                    "CCTV1",
                    EpgProgrammeList(
                        listOf(
                            EpgProgramme(
                                startAt = System.currentTimeMillis() - 2000000,
                                endAt = System.currentTimeMillis() - 1000000,
                                title = "新闻联播"
                            ),
                            EpgProgramme(
                                startAt = System.currentTimeMillis() - 1000000,
                                endAt = System.currentTimeMillis() + 1000000,
                                title = "新闻联播1"
                            ),
                            EpgProgramme(
                                startAt = System.currentTimeMillis() + 1000000,
                                endAt = System.currentTimeMillis() + 2000000,
                                title = "新闻联播2"
                            ),
                        )
                    ),
                )
            },
        )
    }
}