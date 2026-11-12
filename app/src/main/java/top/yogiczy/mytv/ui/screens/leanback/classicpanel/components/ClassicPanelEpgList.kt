package top.yogiczy.mytv.ui.screens.leanback.classicpanel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.ListItemDefaults
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.entities.epgProgrammeKey
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.utils.initialScrollIndex
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 经典面板节目单默认选哪天：今天有节目就选今天；今天那天没节目时
 * （节目单过期、只剩前几天的，或源只给了从明天起的数据），原来写死
 * 今天，节目列表按当天取不到就显示空白、右侧日期栏也没有选中项，
 * 明明有节目却要用户自己先点一天才看得到。现在回落到有节目的第一天，
 * 至少先看到内容；今天有节目时行为和原来一字不变。日期栏的键是
 * groupBy 按节目顺序拼的，第一天就是最早有节目的那天。
 * 顶层函数方便单元测试直接调用。
 */
internal fun initialEpgDay(days: List<String>, today: String): String =
    if (today in days) today else days.firstOrNull() ?: today

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LeanbackClassicPanelEpgList(
    modifier: Modifier = Modifier,
    epgProvider: () -> Epg? = { Epg() },
    exitFocusRequesterProvider: () -> FocusRequester = { FocusRequester.Default },
    onUserAction: () -> Unit = {},
) {
    // 日期格式器记住复用：每次重组都新建是纯浪费，焦点移动就会重组
    val dateFormat = remember { SimpleDateFormat("E MM-dd", Locale.getDefault()) }
    val epg = epgProvider()

    if (epg != null && epg.programmes.isNotEmpty()) {
        val programmesGroup = remember(epg) {
            epg.programmes.groupBy { dateFormat.format(it.startAt) }
        }
        // 日期栏的日期列表也只拼一次记下来：原来每次组合都把分组的键
        // 再拼成一份新列表，焦点一动就拼一遍
        val days = remember(programmesGroup) { programmesGroup.keys.toList() }

        // 默认选哪天：今天有节目就选今天；今天那天没节目时回落到有节目
        // 的第一天（见 initialEpgDay），节目列表不再显示空白
        var currentDay by remember(programmesGroup) {
            mutableStateOf(
                initialEpgDay(days, dateFormat.format(System.currentTimeMillis()))
            )
        }
        val programmes = remember(currentDay, programmesGroup) {
            programmesGroup.getOrElse(currentDay) { emptyList() }
        }

        // 今天/明天/后天三个标签只算一次记下来：原来每个日期项每次组合
        // 都现算 3 次 Calendar 再格式化，日期栏有几个项就重复算几份，
        // 焦点一动又全重算一遍；三个值只和今天有关，所有项共用同一份。
        // 和 currentDay 一样只在节目单分组变了时重算，跨夜不刷新——
        // 面板开着跨夜本来节目单本身也不会自己变，保持一致。
        // 今天/明天/后天按日历天数加：直接加 24 小时在夏令时切换那天会差一小时——
        // 秋天那天有 25 小时，凌晨算出来的“明天”还是今天；春天那天 23 小时，
        // 半夜算出来的“明天”已经是后天，日期栏会标错。国内没夏令时不受影响，
        // 但在有时令切换的地区一年会错两次，改法和第 109 轮数据层同一个思路
        val dayLabels = remember(programmesGroup) {
            fun dayString(offsetDays: Int): String {
                return dateFormat.format(
                    Calendar.getInstance().apply {
                        timeInMillis = System.currentTimeMillis()
                        add(Calendar.DAY_OF_YEAR, offsetDays)
                    }.timeInMillis
                )
            }
            Triple(dayString(0), dayString(1), dayString(2))
        }
        val programmesListState = remember(programmes) {
            LazyListState(initialScrollIndex(programmes.indexOfFirst { it.isLive() }))
        }
        val daysListState = remember(programmesGroup) {
            LazyListState(initialScrollIndex(programmesGroup.keys.indexOf(currentDay)))
        }

        LaunchedEffect(programmesListState) {
            snapshotFlow { programmesListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { _ -> onUserAction() }
        }
        LaunchedEffect(daysListState) {
            snapshotFlow { daysListState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { _ -> onUserAction() }
        }

        Row {
            LazyColumn(
                state = programmesListState,
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier
                    .fillMaxHeight()
                    .width(240.dp)
                    .background(MaterialTheme.colorScheme.background.copy(0.7f))
                    .focusProperties {
                        exit = {
                            if (it == FocusDirection.Left) exitFocusRequesterProvider()
                            else FocusRequester.Default
                        }
                    },
            ) {
                itemsIndexed(
                    programmes,
                    // 下标 + 节目内容作 key：只按节目对象设键，源里同一条
                    // 节目被写了两遍时会撞键让列表直接崩掉；下标是列表
                    // 里的位置，同一份列表里不会重复
                    key = { index, it -> epgProgrammeKey(index, it) },
                ) { _, programme ->
                    LeanbackClassicPanelEpgItem(
                        epgProgrammeProvider = { programme },
                    )
                }
            }

            if (programmesGroup.size > 1) {
                LazyColumn(
                    state = daysListState,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = modifier
                        .fillMaxHeight()
                        .width(100.dp)
                        .background(MaterialTheme.colorScheme.background.copy(0.7f))
                ) {

                    items(days, key = { it }) {
                        LeanbackClassicPanelEpgDayItem(
                            dayProvider = { it },
                            currentDayProvider = { currentDay },
                            onChangeCurrentDay = { currentDay = it },
                            todayProvider = { dayLabels.first },
                            tomorrowProvider = { dayLabels.second },
                            dayAfterTomorrowProvider = { dayLabels.third },
                        )
                    }
                }
            }
        }
    } else {
        // 当前频道没有节目单时也给一句提示：原来这里整列是空的，按右键
        // 展开节目单却什么都没有，用户分不清是没加载出来还是本来就没有；
        // 普通面板的节目单弹窗同样情况显示“当前频道暂无节目”，这边和它一致
        Column(
            modifier = modifier
                .fillMaxHeight()
                .width(240.dp)
                .background(MaterialTheme.colorScheme.background.copy(0.7f))
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "当前频道暂无节目",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LeanbackClassicPanelEpgItem(
    modifier: Modifier = Modifier,
    epgProgrammeProvider: () -> EpgProgramme = { EpgProgramme() },
) {
    val programme = epgProgrammeProvider()
    // 时间格式器记住复用：每条节目行焦点一动就重组一次，每次都新建
    // 格式器是纯浪费；普通面板的节目单（PanelIptvEpg）早就是这么做的
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // “正在播吗”只算一次记下来：第 143 轮节目单弹窗同样办法——
    // 原来同一条节目每次组合要算两遍，选中状态一次、播放小图标
    // 又算一次，每遍都重新取一次当前时间；同一轮组合里两遍结果
    // 必然相同，记下来两处共用，还避免两遍取时间正好跨过节目
    // 边界、选中和图标对不上
    val isLive = programme.isLive()

    // 时间串只拼一次记下来：原来每次组合都在标题上方现拼一次
    // “开始 ~ 结束”，焦点一动、每条节目行重组一次就重拼一次；
    // 开始结束时间不变，拼出来的串也不会变，节目变了才重拼
    val timeText = remember(programme) {
        "${timeFormat.format(programme.startAt)}  ~ ${timeFormat.format(programme.endAt)}"
    }

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalContentColor provides if (isFocused) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onBackground
    ) {
        androidx.tv.material3.ListItem(
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused || it.hasFocus
                }
                .handleLeanbackKeyEvents(
                    onSelect = {
                        focusRequester.requestFocus()
                    },
                ),
            colors = ListItemDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.onBackground,
                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = 0.5f
                ),
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
                    Icon(Icons.Default.PlayArrow, contentDescription = "playing")
                }
            },
        )
    }
}

@Composable
private fun LeanbackClassicPanelEpgDayItem(
    modifier: Modifier = Modifier,
    dayProvider: () -> String = { "" },
    currentDayProvider: () -> String = { "" },
    onChangeCurrentDay: () -> Unit = {},
    todayProvider: () -> String = { "" },
    tomorrowProvider: () -> String = { "" },
    dayAfterTomorrowProvider: () -> String = { "" },
) {
    val day = dayProvider()

    // 日期字符串只切一次记下来：原来每次组合都在标题里现切一次，
    // 焦点一动、日期栏里每个项都要重切一份；日期不变切出来的结果也不会变
    val dayParts = remember(day) { day.split(" ") }

    val focusRequester = remember { FocusRequester() }
    val isSelected by remember(currentDayProvider()) { derivedStateOf { day == currentDayProvider() } }
    var isFocused by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalContentColor provides if (isFocused) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onBackground
    ) {
        androidx.tv.material3.ListItem(
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused || it.hasFocus
                }
                .handleLeanbackKeyEvents(
                    onSelect = {
                        if (isFocused) onChangeCurrentDay()
                        else focusRequester.requestFocus()
                    }
                ),
            colors = ListItemDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.onBackground,
                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = 0.5f
                ),
            ),
            selected = isSelected,
            onClick = {},
            headlineContent = {
                Column {
                    Text(
                        text = when (day) {
                            todayProvider() -> "今天"
                            tomorrowProvider() -> "明天"
                            dayAfterTomorrowProvider() -> "后天"
                            else -> dayParts[0]
                        },
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = dayParts[1],
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            },
        )
    }
}

@Preview
@Composable
private fun LeanbackClassicPanelEpgListPreview() {
    LeanbackTheme {
        LeanbackClassicPanelEpgList(
            epgProvider = {
                Epg(
                    channel = "CCTV1", programmes = EpgProgrammeList(
                        List(200) { index ->
                            EpgProgramme(
                                title = "节目$index",
                                startAt = System.currentTimeMillis() - 3600 * 1000 * 24 * 5 + index * 3600 * 1000,
                                endAt = System.currentTimeMillis() - 3600 * 1000 * 24 * 5 + index * 3600 * 1000 + 3600 * 1000
                            )
                        }
                    )
                )
            }
        )
    }
}