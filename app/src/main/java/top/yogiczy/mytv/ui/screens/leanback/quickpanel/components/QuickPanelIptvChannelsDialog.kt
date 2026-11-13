package top.yogiczy.mytv.ui.screens.leanback.quickpanel.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.IOException
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.swallowUnhandledSelectKeys
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.utils.initialScrollIndex
import top.yogiczy.mytv.utils.isIPv6
import kotlin.system.measureTimeMillis

@Composable
fun LeanbackQuickPanelIptvChannelsDialog(
    modifier: Modifier = Modifier,
    showDialogProvider: () -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
    iptvProvider: () -> Iptv = { Iptv() },
    iptvUrlIdxProvider: () -> Int = { 0 },
    onIptvUrlIdxChange: (Int) -> Unit = {},
    onUserAction: () -> Unit = {},
) {
    val iptv = iptvProvider()
    val iptvUrlIdx = iptvUrlIdxProvider()

    if (showDialogProvider()) {
        AlertDialog(
            modifier = modifier.swallowUnhandledSelectKeys(),
            onDismissRequest = onDismissRequest,
            confirmButton = { Text(text = "短按切换线路") },
            title = { Text(text = iptv.name) },
            text = {
                var hasFocused by remember { mutableStateOf(false) }

                val listState = rememberLazyListState(initialScrollIndex(iptvUrlIdx))

                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { _ -> onUserAction() }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 按"下标+地址"设 key：列表变化时测速结果与焦点跟着地址走，
                    // 而不是错位到同一位置的新地址上；纯下标做 key 等于没 key
                    itemsIndexed(iptv.urlList, key = { index, url -> "$index:$url" }) { index, url ->
                        val focusRequester = remember { FocusRequester() }

                        LaunchedEffect(Unit) {
                            if (index == iptvUrlIdx && !hasFocused) {
                                hasFocused = true
                                focusRequester.requestFocus()
                            }
                        }

                        LeanbackQuickPanelIptvChannelItem(
                            url = url,
                            urlIndex = index,
                            isSelected = index == iptvUrlIdx,
                            focusRequester = focusRequester,
                            onSelect = {
                                onDismissRequest()
                                onIptvUrlIdxChange(index)
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun LeanbackQuickPanelIptvChannelItem(
    modifier: Modifier = Modifier,
    url: String,
    urlIndex: Int,
    isSelected: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSelect: () -> Unit = {},
    urlDelay: Long = rememberIptvUrlDelay(url),
) {
    var isFocused by remember { mutableStateOf(false) }

    androidx.tv.material3.ListItem(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .handleLeanbackKeyEvents(
                onSelect = {
                    if (isFocused) onSelect()
                    else focusRequester.requestFocus()
                },
            ),
        selected = isSelected,
        onClick = { },
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                androidx.tv.material3.Text(text = "线路${urlIndex + 1}")

                Row(
                    modifier = Modifier.padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompositionLocalProvider(
                        androidx.tv.material3.LocalTextStyle provides MaterialTheme.typography.labelSmall,
                        androidx.tv.material3.LocalContentColor provides androidx.tv.material3.LocalContentColor.current.copy(
                            alpha = 0.8f
                        ),
                    ) {
                        val textModifier = Modifier
                            .background(
                                androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.3f),
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(vertical = 2.dp, horizontal = 4.dp)

                        androidx.tv.material3.Text(
                            text = if (url.isIPv6()) "IPV6" else "IPV4",
                            modifier = textModifier,
                        )

                        if (urlDelay != 0L) {
                            androidx.tv.material3.Text(
                                text = "$urlDelay ms",
                                modifier = textModifier,
                            )
                        }
                    }
                }
            }
        },
        supportingContent = {
            androidx.tv.material3.Text(
                text = url,
                maxLines = 1,
            )
        },
        trailingContent = {
            if (isSelected) {
                androidx.tv.material3.Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已选中",
                )
            }
        },
    )
}

@Composable
private fun rememberIptvUrlDelay(url: String): Long {
    var elapsedTime by remember { mutableLongStateOf(0) }
    var hasError by remember { mutableStateOf(false) }

    // 按 url 设 key：同一位置换了地址要重新测速，而不是沿用旧地址的延迟
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            elapsedTime = measureTimeMillis {
                try {
                    val request = Request.Builder().url(url).build()
                    sharedHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    }
                } catch (_: Exception) {
                    // 测速失败（超时、非法 URL、rtmp 等不支持的协议……）：
                    // 只隐藏延迟标签，不要把异常抛到组合作用域导致闪退；
                    // 原先只 catch IOException，Request.Builder 在非法
                    // URL 上抛的 IllegalArgumentException 会直接崩应用
                    hasError = true
                }
            }
        }
    }

    return if (hasError) 0 else elapsedTime
}

@Preview
@Composable
private fun LeanbackQuickPanelIptvChannelItemPreview() {
    LeanbackTheme {
        LeanbackQuickPanelIptvChannelItem(
            url = "http://dbiptv.sn.chinamobile.com/PLTV/88888890/224/3221226231/index.m3u8",
            urlIndex = 0,
            urlDelay = 123,
            isSelected = true,
        )
    }
}

@Preview
@Composable
private fun LeanbackQuickPanelIptvChannelsDialogPreview() {
    LeanbackTheme {
        LeanbackQuickPanelIptvChannelsDialog(
            showDialogProvider = { true },
            iptvProvider = { Iptv.EXAMPLE },
        )
    }
}