package top.yogiczy.mytv.ui.screens.leanback.settings.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.launch
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.screens.leanback.update.LeanBackUpdateViewModel
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP

@Composable
fun LeanbackSettingsCategoryApp(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
    updateViewModel: LeanBackUpdateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "开机自启",
                supportingContent = "请确保当前设备支持该功能",
                trailingContent = {
                    Switch(checked = settingsViewModel.appBootLaunch, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.appBootLaunch = !settingsViewModel.appBootLaunch
                },
            )
        }

        item {
            val context = LocalContext.current

            LeanbackSettingsCategoryListItem(
                headlineContent = "显示模式",
                supportingContent = "短按切换应用显示模式",
                trailingContent = when (settingsViewModel.appDeviceDisplayType) {
                    SP.AppDeviceDisplayType.LEANBACK -> "TV"
                    SP.AppDeviceDisplayType.PAD -> "Pad"
                    SP.AppDeviceDisplayType.MOBILE -> "手机"
                },
                onSelected = {
                    Toast.makeText(context, "暂未开放", Toast.LENGTH_SHORT).show()
//                    settingsViewModel.appDeviceDisplayType = SP.AppDeviceDisplayType.entries[
//                        (settingsViewModel.appDeviceDisplayType.ordinal + 1) % SP.AppDeviceDisplayType.entries.size
//                    ]
                },
            )
        }

        item {
            // 更新检查只在启动后跑一次（UpdateScreen，3 秒延迟）：那次失败
            // （断网）后原来没有任何手动重查入口，"应用更新"行还显示
            // "最新版本：v0.0.0"——0.0.0 只是 GitRelease 的默认值
            LeanbackSettingsCategoryListItem(
                headlineContent = "应用更新",
                supportingContent = when {
                    updateViewModel.isUpdateAvailable ->
                        "最新版本：v${updateViewModel.latestRelease.version}"

                    updateViewModel.isCheckingUpdate -> "正在检查…"
                    updateViewModel.checkCompleted -> "已是最新版本"
                    else -> "尚未检查，点击手动检查"
                },
                trailingContent = when {
                    updateViewModel.isUpdateAvailable -> "发现新版本"
                    updateViewModel.isCheckingUpdate -> "检查中"
                    updateViewModel.checkCompleted -> "无更新"
                    else -> "未检查"
                },
                onSelected = {
                    if (updateViewModel.isUpdateAvailable) {
                        updateViewModel.showDialog = true
                    } else if (!updateViewModel.isCheckingUpdate) {
                        coroutineScope.launch {
                            updateViewModel.checkUpdate(
                                context.packageManager
                                    .getPackageInfo(context.packageName, 0)
                                    .versionName.orEmpty()
                            )
                            if (updateViewModel.isUpdateAvailable) {
                                updateViewModel.showDialog = true
                            } else if (updateViewModel.checkCompleted) {
                                LeanbackToastState.I.showToast("已是最新版本")
                            } else {
                                LeanbackToastState.I.showToast("检查更新失败")
                            }
                        }
                    }
                },
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryAppPreview() {
    SP.init(LocalContext.current)
    LeanbackTheme {
        LeanbackSettingsCategoryApp(
            modifier = Modifier.padding(20.dp),
            settingsViewModel = LeanbackSettingsViewModel(),
        )
    }
}
