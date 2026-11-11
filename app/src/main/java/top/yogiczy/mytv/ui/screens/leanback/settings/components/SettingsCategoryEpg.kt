package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.collections.immutable.toImmutableList
import top.yogiczy.mytv.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.main.LeanbackMainViewModel
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.nextHourStep

@Composable
fun LeanbackSettingsCategoryEpg(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
    // 与主界面同一个 Activity 作用域的实例；切换节目单后立即重载，否则要重启应用才生效
    mainViewModel: LeanbackMainViewModel = viewModel(),
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "节目单启用",
                supportingContent = "首次加载时可能会有跳帧风险",
                trailingContent = {
                    Switch(checked = settingsViewModel.epgEnable, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.epgEnable = !settingsViewModel.epgEnable
                    // 开关只在刷新时读：不刷新就得重启才生效
                    mainViewModel.refresh()
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "节目单刷新时间阈值",
                supportingContent = "短按增加1小时，长按设为0小时；时间不到${settingsViewModel.epgRefreshTimeThreshold}:00节目单将不会刷新",
                trailingContent = "${settingsViewModel.epgRefreshTimeThreshold}小时",
                onSelected = {
                    settingsViewModel.epgRefreshTimeThreshold =
                        nextHourStep(settingsViewModel.epgRefreshTimeThreshold, 12)
                },
                onLongSelected = {
                    settingsViewModel.epgRefreshTimeThreshold = 0
                },
            )
        }

        item {
            var showDialog by remember { mutableStateOf(false) }

            LeanbackSettingsCategoryListItem(
                headlineContent = "自定义节目单",
                supportingContent = if (settingsViewModel.epgXmlUrl != Constants.EPG_XML_URL)
                    settingsViewModel.epgXmlUrl else null,
                trailingContent = if (settingsViewModel.epgXmlUrl != Constants.EPG_XML_URL) "已启用" else "未启用",
                onSelected = { showDialog = true },
                remoteConfig = true,
            )

            // 历史源对话框与直播源那边共用一份：原来两份除了标题和文案
            // 外一模一样，这里只传节目单自己的字符串和源
            LeanbackSettingsSourceHistoryDialog(
                showDialogProvider = { showDialog },
                onDismissRequest = { showDialog = false },
                title = "历史节目单",
                defaultSource = Constants.EPG_XML_URL,
                defaultSourceLabel = "默认节目单",
                addSourceLabel = "添加其他节目单",
                sourceHistoryProvider = {
                    settingsViewModel.epgXmlUrlHistoryList.filter {
                        it != Constants.EPG_XML_URL
                    }.toImmutableList()
                },
                currentSourceProvider = { settingsViewModel.epgXmlUrl },
                onSelected = {
                    showDialog = false
                    if (settingsViewModel.epgXmlUrl != it) {
                        settingsViewModel.epgXmlUrl = it
                        // clearCache 是同步删文件，直接调：原先包在 launch 里，
                        // 顺序只靠 Main.immediate 的同步执行才没跑偏
                        EpgRepository().clearCache()
                        mainViewModel.refresh()
                    }
                },
                onDeleted = {
                    settingsViewModel.epgXmlUrlHistoryList -= it
                }
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "清除缓存",
                supportingContent = "短按清除节目单缓存文件",
                onSelected = {
                    EpgRepository().clearCache()
                    mainViewModel.refresh()
                    LeanbackToastState.I.showToast("清除缓存成功")
                },
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryEpgPreview() {
    SP.init(LocalContext.current)
    LeanbackTheme {
        LeanbackSettingsCategoryEpg(
            modifier = Modifier.padding(20.dp),
            settingsViewModel = LeanbackSettingsViewModel().apply {
                epgXmlUrl = "https://iptv-org.github.io/epg.xml"
                epgXmlUrlHistoryList = setOf(
                    "https://iptv-org.github.io/epg.xml",
                    "https://iptv-org.github.io/epg2.xml",
                    "https://iptv-org.github.io/epg3.xml",
                )
            },
            mainViewModel = LeanbackMainViewModel(),
        )
    }
}