package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents

@Composable
fun LeanbackSettingsCategoryFavorite(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "收藏启用",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.iptvChannelFavoriteEnable,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.iptvChannelFavoriteEnable =
                        !settingsViewModel.iptvChannelFavoriteEnable
                    if (!settingsViewModel.iptvChannelFavoriteEnable) {
                        settingsViewModel.iptvChannelFavoriteListVisible = false
                    }
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "当前已收藏",
                supportingContent = "包括不存在直播源中的频道",
                trailingContent = "${settingsViewModel.iptvChannelFavoriteList.size}个频道",
            )
        }

        item {
            var showConfirmDialog by remember { mutableStateOf(false) }

            LeanbackSettingsCategoryListItem(
                headlineContent = "清空全部收藏",
                // 原先短按立即清空、无确认：遥控器上按错一行就丢全部收藏
                supportingContent = "清空后无法恢复",
                onSelected = {
                    if (settingsViewModel.iptvChannelFavoriteList.isEmpty()) {
                        LeanbackToastState.I.showToast("暂无收藏")
                    } else {
                        showConfirmDialog = true
                    }
                }
            )

            LeanbackSettingsFavoriteClearConfirmDialog(
                showDialogProvider = { showConfirmDialog },
                onDismissRequest = { showConfirmDialog = false },
                favoriteCountProvider = { settingsViewModel.iptvChannelFavoriteList.size },
                onConfirm = {
                    showConfirmDialog = false
                    settingsViewModel.iptvChannelFavoriteList = emptySet()
                    settingsViewModel.iptvChannelFavoriteListVisible = false
                    LeanbackToastState.I.showToast("已清空全部收藏")
                },
            )
        }
    }
}

@Composable
private fun LeanbackSettingsFavoriteClearConfirmDialog(
    modifier: Modifier = Modifier,
    showDialogProvider: () -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
    favoriteCountProvider: () -> Int = { 0 },
    onConfirm: () -> Unit = {},
) {
    if (showDialogProvider()) {
        val focusRequester = remember { FocusRequester() }

        // 默认焦点落在"取消"上：误触确定键只会关掉对话框，
        // 要清空得主动移到"清空"上再按
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = modifier,
            onDismissRequest = onDismissRequest,
            confirmButton = {
                androidx.tv.material3.Button(
                    onClick = {},
                    modifier = Modifier.handleLeanbackKeyEvents(
                        onSelect = onConfirm,
                    ),
                ) {
                    androidx.tv.material3.Text(text = "清空")
                }
            },
            dismissButton = {
                androidx.tv.material3.Button(
                    onClick = {},
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .handleLeanbackKeyEvents(
                            onSelect = onDismissRequest,
                        ),
                ) {
                    androidx.tv.material3.Text(text = "取消")
                }
            },
            title = { Text(text = "清空全部收藏") },
            text = {
                Text(text = "将删除全部 ${favoriteCountProvider()} 个收藏频道，该操作无法恢复。")
            },
        )
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryFavoritePreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryFavorite(
            modifier = Modifier.padding(20.dp),
            settingsViewModel = LeanbackSettingsViewModel().apply {
                iptvChannelFavoriteList = setOf("CCTV-1", "CCTV-2", "CCTV-3")
            }
        )
    }
}
