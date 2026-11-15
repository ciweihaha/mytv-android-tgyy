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
import top.yogiczy.mytv.utils.getPackageInfoCompat

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
                // 华为智慧屏（HarmonyOS）默认不让第三方应用自启：这里
                // 的开关只是第一道，还要在智慧屏管家 → 应用管理 →
                // 应用启动管理里给本应用开“允许自启动”，否则开机后
                // 系统根本不会拉起应用
                supportingContent = "华为智慧屏还需在智慧屏管家 → 应用管理 → 应用启动管理里允许本应用自启动",
                trailingContent = {
                    Switch(checked = settingsViewModel.appBootLaunch, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.appBootLaunch = !settingsViewModel.appBootLaunch
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "显示模式",
                // 文案与行为一致：点按只弹“暂未开放”，副文案原来写
                // “短按切换应用显示模式”，点了却切不了，对不上
                supportingContent = "暂未开放",
                trailingContent = settingsViewModel.appDeviceDisplayType.label,
                onSelected = {
                    Toast.makeText(context, "暂未开放", Toast.LENGTH_SHORT).show()
                },
            )
        }

        item {
            // 更新检查启动后只跑一次（UpdateScreen，3 秒延迟）：那次失败
            // （断网）后原来没有任何手动重查入口，"应用更新"行还显示
            // "最新版本：v0.0.0"——0.0.0 只是 GitRelease 的默认值。
            // 点按这行会重新联网查：已经知道有更新时也要重查，启动后又
            // 发了新版、只有重查才能拿到，否则一直给旧版、直到重启应用。
            LeanbackSettingsCategoryListItem(
                headlineContent = "应用更新",
                supportingContent = updateSupportingText(
                    updateViewModel.isUpdateAvailable,
                    updateViewModel.isCheckingUpdate,
                    updateViewModel.checkFailed,
                    updateViewModel.checkCompleted,
                    updateViewModel.latestRelease.version,
                ),
                trailingContent = updateTrailingText(
                    updateViewModel.isUpdateAvailable,
                    updateViewModel.isCheckingUpdate,
                    updateViewModel.checkFailed,
                    updateViewModel.checkCompleted,
                ),
                onSelected = {
                    if (!updateViewModel.isCheckingUpdate) {
                        coroutineScope.launch {
                            updateViewModel.checkUpdate(
                                context.packageManager
                                    .getPackageInfoCompat(context.packageName)
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

// “应用更新”行两行文案的优先级：有更新 > 检查中 > 检查失败 > 已查完 > 没查过。
// 原来两段 when 直接写在界面里、测不到；抽出来钉死优先级——重查时旧的
// “有更新”仍在，检查中也不盖掉它；新一轮检查开始会先把 checkCompleted
// 清掉（UpdateViewModel），所以“检查中”和“已查完”不会同时为真。
// 检查失败单独一档：之前失败和从未检查都走“尚未检查”，失败后那行谎称
// 没查过；失败时只有一闪而过的提示，行里留档才对得上。
internal fun updateSupportingText(
    isUpdateAvailable: Boolean,
    isCheckingUpdate: Boolean,
    checkFailed: Boolean,
    checkCompleted: Boolean,
    latestVersion: String,
): String = when {
    isUpdateAvailable -> "最新版本：v$latestVersion"
    isCheckingUpdate -> "正在检查…"
    checkFailed -> "检查更新失败，点击重试"
    checkCompleted -> "已是最新版本"
    else -> "尚未检查，点击手动检查"
}

internal fun updateTrailingText(
    isUpdateAvailable: Boolean,
    isCheckingUpdate: Boolean,
    checkFailed: Boolean,
    checkCompleted: Boolean,
): String = when {
    isUpdateAvailable -> "发现新版本"
    isCheckingUpdate -> "检查中"
    checkFailed -> "检查失败"
    checkCompleted -> "无更新"
    else -> "未检查"
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
