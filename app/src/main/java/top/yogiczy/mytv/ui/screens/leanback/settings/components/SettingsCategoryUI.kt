package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.humanizeMs
import java.text.DecimalFormat

// 缩放档位：界面整体缩放和界面字体缩放共用同一套 1.0~2.0、步进 0.1；
// 原来两项里各写了一份最小/最大/步进和同样的步进逻辑，改一处容易漏另一处
private const val SCALE_MIN = 1f
private const val SCALE_MAX = 2f
private const val SCALE_STEP = 0.1f

// 下一档缩放：到顶回最小，否则加一档钳在范围内；长按恢复默认就是 SCALE_MIN。
// 顶层函数方便单元测试直接调用。
internal fun nextScaleRatio(current: Float): Float =
    if (current >= SCALE_MAX) SCALE_MIN
    else (current + SCALE_STEP).coerceIn(SCALE_MIN, SCALE_MAX)

@Composable
fun LeanbackSettingsCategoryUI(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
) {
    // DecimalFormat 只在主线程重组里用，无线程安全问题；两个缩放项共用一个，
    // 免得每次重组各现造一个
    val scaleFormat = remember { DecimalFormat("#.#") }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "节目进度",
                supportingContent = "在频道项底部显示当前节目进度条",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.uiShowEpgProgrammeProgress,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.uiShowEpgProgrammeProgress =
                        !settingsViewModel.uiShowEpgProgrammeProgress
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "经典选台界面",
                supportingContent = "将选台界面替换为经典三段式结构",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.uiUseClassicPanelScreen,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.uiUseClassicPanelScreen =
                        !settingsViewModel.uiUseClassicPanelScreen
                },
            )
        }

        item {
            val timeShowRangeSeconds = Constants.UI_TIME_SHOW_RANGE / 1000

            LeanbackSettingsCategoryListItem(
                headlineContent = "时间显示",
                supportingContent = when (settingsViewModel.uiTimeShowMode) {
                    SP.UiTimeShowMode.HIDDEN -> "不显示时间"
                    SP.UiTimeShowMode.ALWAYS -> "总是显示时间"
                    SP.UiTimeShowMode.EVERY_HOUR -> "整点前后${timeShowRangeSeconds}s显示时间"
                    SP.UiTimeShowMode.HALF_HOUR -> "半点前后${timeShowRangeSeconds}s显示时间"
                },
                trailingContent = settingsViewModel.uiTimeShowMode.label,
                onSelected = {
                    settingsViewModel.uiTimeShowMode =
                        settingsViewModel.uiTimeShowMode.next()
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "画中画模式",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.uiPipMode,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.uiPipMode =
                        !settingsViewModel.uiPipMode
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "超时自动关闭界面",
                supportingContent = "影响选台界面，快捷操作界面",
                trailingContent = Constants.UI_SCREEN_AUTO_CLOSE_DELAY.humanizeMs(),
                locK = true,
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "界面整体缩放比例",
                supportingContent = "短按切换缩放比例，长按恢复默认；部分界面受影响",
                trailingContent = "×${scaleFormat.format(settingsViewModel.uiDensityScaleRatio)}",
                onSelected = {
                    settingsViewModel.uiDensityScaleRatio =
                        nextScaleRatio(settingsViewModel.uiDensityScaleRatio)
                },
                onLongSelected = {
                    settingsViewModel.uiDensityScaleRatio = SCALE_MIN
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "界面字体缩放比例",
                supportingContent = "短按切换缩放比例，长按恢复默认；部分界面受影响",
                trailingContent = "×${scaleFormat.format(settingsViewModel.uiFontScaleRatio)}",
                onSelected = {
                    settingsViewModel.uiFontScaleRatio =
                        nextScaleRatio(settingsViewModel.uiFontScaleRatio)
                },
                onLongSelected = {
                    settingsViewModel.uiFontScaleRatio = SCALE_MIN
                },
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryUIPreview() {
    SP.init(LocalContext.current)
    LeanbackTheme {
        LeanbackSettingsCategoryUI(
            modifier = Modifier.padding(20.dp),
            settingsViewModel = LeanbackSettingsViewModel(),
        )
    }
}