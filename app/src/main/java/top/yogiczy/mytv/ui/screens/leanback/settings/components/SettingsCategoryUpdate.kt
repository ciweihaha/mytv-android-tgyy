package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.theme.LeanbackTheme

@Composable
fun LeanbackSettingsCategoryUpdate(
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
                headlineContent = "更新强提醒",
                supportingContent = updateForceRemindSupportingText(settingsViewModel.updateForceRemind),
                trailingContent = {
                    Switch(checked = settingsViewModel.updateForceRemind, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.updateForceRemind = !settingsViewModel.updateForceRemind
                },
            )
        }
    }
}

/**
 * “更新强提醒”行的说明文字：开 → 检测到新版本时弹窗提醒，关 →
 * 只用提示条提示。从界面里抽出来，只为能离线断言开/关两档写没
 * 写对；行为逐字不变。
 */
internal fun updateForceRemindSupportingText(forceRemind: Boolean): String =
    if (forceRemind) "检测到新版本时会弹窗提醒"
    else "检测到新版本时仅用提示条提示"

@Preview
@Composable
private fun LeanbackSettingsCategoryUpdatePreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryUpdate(
            modifier = Modifier.padding(20.dp),
        )
    }
}
