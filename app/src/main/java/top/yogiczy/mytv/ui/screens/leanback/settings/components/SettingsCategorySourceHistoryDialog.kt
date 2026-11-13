package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackQrcodeDialog
import top.yogiczy.mytv.ui.utils.HttpServer
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.utils.initialScrollIndex

/**
 * 历史源对话框（直播源 / 节目单共用）：原来两份对话框除了标题、
 * 默认源文案、添加文案三处字符串外一模一样，各自复制了一份，
 * 改一处容易漏另一处；现在只留这一份，两边只传不同的字符串和源。
 */
@Composable
fun LeanbackSettingsSourceHistoryDialog(
    modifier: Modifier = Modifier,
    showDialogProvider: () -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
    title: String = "",
    defaultSource: String = "",
    defaultSourceLabel: String = "",
    addSourceLabel: String = "",
    sourceHistoryProvider: () -> ImmutableList<String> = { persistentListOf() },
    currentSourceProvider: () -> String = { "" },
    onSelected: (String) -> Unit = {},
    onDeleted: (String) -> Unit = {},
) {
    val sourceHistory = listOf(defaultSource) + sourceHistoryProvider()
    val currentSource = currentSourceProvider()

    if (showDialogProvider()) {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = modifier,
            onDismissRequest = onDismissRequest,
            confirmButton = { Text(text = "短按切换；长按删除历史记录") },
            title = { Text(title) },
            text = {
                var hasFocused by remember { mutableStateOf(false) }

                // 列表状态必须 remember：原先每次重组都新建，滚动位置会被重置
                val listState = remember(sourceHistory, currentSource) {
                    LazyListState(
                        initialScrollIndex(sourceHistory.indexOf(currentSource)),
                    )
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sourceHistory, key = { it }) { source ->
                        val focusRequester = remember { FocusRequester() }
                        var isFocused by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            if (source == currentSource && !hasFocused) {
                                hasFocused = true
                                focusRequester.requestFocus()
                            }
                        }

                        androidx.tv.material3.ListItem(
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                                .handleLeanbackKeyEvents(
                                    onSelect = {
                                        if (isFocused) onSelected(source)
                                        else focusRequester.requestFocus()
                                    },
                                    onLongSelect = {
                                        if (isFocused) onDeleted(source)
                                        else focusRequester.requestFocus()
                                    },
                                ),
                            selected = currentSource == source,
                            onClick = { },
                            headlineContent = {
                                androidx.tv.material3.Text(
                                    text = if (source == defaultSource) defaultSourceLabel else source,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = if (isFocused) Int.MAX_VALUE else 2,
                                )
                            },
                            trailingContent = {
                                if (currentSource == source) {
                                    androidx.tv.material3.Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "已选中",
                                    )
                                }
                            },
                        )
                    }

                    item {
                        val focusRequester = remember { FocusRequester() }
                        var isFocused by remember { mutableStateOf(false) }
                        var showDialog by remember { mutableStateOf(false) }

                        androidx.tv.material3.ListItem(
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
                                .handleLeanbackKeyEvents(
                                    onSelect = {
                                        if (isFocused) showDialog = true
                                        else focusRequester.requestFocus()
                                    },
                                ),
                            selected = false,
                            onClick = {},
                            headlineContent = {
                                androidx.tv.material3.Text(addSourceLabel)
                            },
                        )

                        LeanbackQrcodeDialog(
                            textProvider = { HttpServer.serverUrl },
                            description = "扫码前往设置页面",
                            showDialogProvider = { showDialog },
                            onDismissRequest = { showDialog = false },
                        )
                    }
                }
            },
        )
    }
}
