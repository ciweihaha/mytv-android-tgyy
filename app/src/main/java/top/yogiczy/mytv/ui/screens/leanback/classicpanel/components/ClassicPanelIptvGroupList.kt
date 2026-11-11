package top.yogiczy.mytv.ui.screens.leanback.classicpanel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.ListItemDefaults
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.utils.initialScrollIndex

/**
 * 经典面板分组列表的 key：分组名前面再带上下标。分组名是解析时 groupBy
 * 的键、源里天然唯一，但经典面板会在列表最前面再拼一个“我的收藏”
 * 假分组——源里要是也有个同名分组，两行就撞了同一个 key，列表
 * 状态会串行、甚至直接报错；下标是列表里的位置，同一轮列表里
 * 不会重复。顶层函数方便单元测试直接调用。
 */
internal fun classicPanelGroupKey(index: Int, name: String): String = "$index:$name"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LeanbackClassicPanelIptvGroupList(
    modifier: Modifier = Modifier,
    iptvGroupListProvider: () -> IptvGroupList = { IptvGroupList() },
    initialIptvGroupProvider: () -> IptvGroup = { IptvGroup() },
    exitFocusRequesterProvider: () -> FocusRequester = { FocusRequester.Default },
    onIptvGroupFocused: (IptvGroup) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val iptvGroupList = iptvGroupListProvider()
    val initialIptvGroup = initialIptvGroupProvider()

    // 初始滚动位置只算一次记下来：原来每次组合都现算一遍初始分组在
    // 列表里的下标，算完只在第一次建列表状态时用一次，之后滚动位置
    // 由列表状态自己记住；只在直播源变了时重算
    val initialGroupIdx = remember(iptvGroupList) {
        initialScrollIndex(iptvGroupList.indexOf(initialIptvGroup))
    }
    val listState = rememberLazyListState(initialGroupIdx)

    val focusRequester = remember { FocusRequester() }
    var focusedIptvGroup by remember { mutableStateOf(initialIptvGroup) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { _ -> onUserAction() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .width(140.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background.copy(0.9f))
            .focusRequester(focusRequester)
            .focusProperties {
                exit = {
                    focusRequester.saveFocusedChild()
                    exitFocusRequesterProvider()
                }
                enter = {
                    if (focusRequester.restoreFocusedChild()) FocusRequester.Cancel
                    else FocusRequester.Default
                }
            },
    ) {
        itemsIndexed(iptvGroupList, key = { index, iptvGroup ->
            classicPanelGroupKey(index, iptvGroup.name)
        }) { _, iptvGroup ->
            val isSelected by remember { derivedStateOf { iptvGroup == focusedIptvGroup } }

            LeanbackClassicPanelIptvGroupItem(
                iptvGroupProvider = { iptvGroup },
                isSelectedProvider = { isSelected },
                initialFocusedProvider = { iptvGroup == initialIptvGroup },
                onFocused = {
                    focusedIptvGroup = it
                    onIptvGroupFocused(it)
                },
            )
        }
    }
}

@Composable
private fun LeanbackClassicPanelIptvGroupItem(
    modifier: Modifier = Modifier,
    iptvGroupProvider: () -> IptvGroup = { IptvGroup() },
    isSelectedProvider: () -> Boolean = { false },
    initialFocusedProvider: () -> Boolean = { false },
    onFocused: (IptvGroup) -> Unit = {},
) {
    val iptvGroup = iptvGroupProvider()

    val focusRequester = remember { FocusRequester() }
    var hasFocused by rememberSaveable { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasFocused && initialFocusedProvider()) {
            focusRequester.requestFocus()
        }
        hasFocused = true
    }

    CompositionLocalProvider(
        LocalContentColor provides if (isFocused) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onBackground
    ) {
        androidx.tv.material3.ListItem(
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused || it.hasFocus

                    if (isFocused) {
                        onFocused(iptvGroup)
                    }
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
            selected = isSelectedProvider(),
            onClick = { },
            headlineContent = {
                Text(
                    text = iptvGroup.name,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@Preview
@Composable
private fun LeanbackClassicPanelIptvGroupListPreview() {
    LeanbackTheme {
        LeanbackClassicPanelIptvGroupList(
            modifier = Modifier.padding(20.dp),
            iptvGroupListProvider = { IptvGroupList.EXAMPLE },
        )
    }
}