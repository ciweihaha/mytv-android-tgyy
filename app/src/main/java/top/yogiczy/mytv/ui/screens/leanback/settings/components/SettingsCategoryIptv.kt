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
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.main.LeanbackMainViewModel
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.humanizeMs

@Composable
fun LeanbackSettingsCategoryIptv(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
    // 与主界面同一个 Activity 作用域的实例；切换源后立即重载，否则要重启应用才生效
    mainViewModel: LeanbackMainViewModel = viewModel(),
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "数字选台",
                supportingContent = "通过数字选择频道",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.iptvChannelNoSelectEnable,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.iptvChannelNoSelectEnable =
                        !settingsViewModel.iptvChannelNoSelectEnable
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "换台反转",
                supportingContent = if (settingsViewModel.iptvChannelChangeFlip) "方向键上：下一个频道；方向键下：上一个频道"
                else "方向键上：上一个频道；方向键下：下一个频道",
                trailingContent = {
                    Switch(
                        checked = settingsViewModel.iptvChannelChangeFlip,
                        onCheckedChange = null
                    )
                },
                onSelected = {
                    settingsViewModel.iptvChannelChangeFlip =
                        !settingsViewModel.iptvChannelChangeFlip
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "直播源精简",
                supportingContent = if (settingsViewModel.iptvSourceSimplify) "显示精简直播源(仅央视、地方卫视)" else "显示完整直播源",
                trailingContent = {
                    Switch(checked = settingsViewModel.iptvSourceSimplify, onCheckedChange = null)
                },
                onSelected = {
                    settingsViewModel.iptvSourceSimplify = !settingsViewModel.iptvSourceSimplify
                    // 精简只在加载时读 SP：不刷新就得重启才生效；缓存存的
                    // 是原始源文本，解析时再过滤，所以不用清缓存
                    mainViewModel.refresh()
                },
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "直播源缓存时间",
                supportingContent = "短按增加1小时，长按设为0小时",
                trailingContent = settingsViewModel.iptvSourceCacheTime.humanizeMs(),
                onSelected = {
                    settingsViewModel.iptvSourceCacheTime =
                        (settingsViewModel.iptvSourceCacheTime + 1 * 1000 * 60 * 60) % (1000 * 60 * 60 * 24)
                },
                onLongSelected = {
                    settingsViewModel.iptvSourceCacheTime = 0
                },
            )
        }

        item {
            var showDialog by remember { mutableStateOf(false) }

            LeanbackSettingsCategoryListItem(
                headlineContent = "自定义直播源",
                supportingContent = if (settingsViewModel.iptvSourceUrl != Constants.IPTV_SOURCE_URL) settingsViewModel.iptvSourceUrl else null,
                trailingContent = if (settingsViewModel.iptvSourceUrl != Constants.IPTV_SOURCE_URL) "已启用" else "未启用",
                onSelected = { showDialog = true },
                remoteConfig = true,
            )

            // 历史源对话框与节目单那边共用一份：原来两份除了标题和文案
            // 外一模一样，这里只传直播源自己的字符串和源
            LeanbackSettingsSourceHistoryDialog(showDialogProvider = { showDialog },
                onDismissRequest = { showDialog = false },
                title = "历史直播源",
                defaultSource = Constants.IPTV_SOURCE_URL,
                defaultSourceLabel = "默认直播源",
                addSourceLabel = "添加其他直播源",
                sourceHistoryProvider = {
                    settingsViewModel.iptvSourceUrlHistoryList.filter {
                        it != Constants.IPTV_SOURCE_URL
                    }.toImmutableList()
                },
                currentSourceProvider = { settingsViewModel.iptvSourceUrl },
                onSelected = {
                    showDialog = false
                    if (settingsViewModel.iptvSourceUrl != it) {
                        settingsViewModel.iptvSourceUrl = it
                        // clearCache 是同步删文件，直接调：原先包在 launch 里，
                        // 顺序只靠 Main.immediate 的同步执行才没跑偏，换个
                        // dispatcher 就会先刷新、后清缓存，读到旧源数据
                        IptvRepository().clearCache()
                        mainViewModel.refresh()
                    }
                },
                onDeleted = {
                    settingsViewModel.iptvSourceUrlHistoryList -= it
                })
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "清除缓存",
                supportingContent = "短按清除直播源缓存文件、可播放域名列表",
                onSelected = {
                    settingsViewModel.iptvPlayableHostList = emptySet()
                    IptvRepository().clearCache()
                    mainViewModel.refresh()
                    LeanbackToastState.I.showToast("清除缓存成功")
                },
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryIptvPreview() {
    SP.init(LocalContext.current)
    LeanbackTheme {
        LeanbackSettingsCategoryIptv(
            modifier = Modifier.padding(20.dp),
            settingsViewModel = LeanbackSettingsViewModel().apply {
                iptvSourceCacheTime = 3_600_000
                iptvSourceUrl = "https://iptv-org.github.io/iptv/iptv.m3u"
                iptvSourceUrlHistoryList = setOf(
                    "https://iptv-org.github.io/iptv/iptv.m3u",
                    "https://iptv-org.github.io/iptv/iptv2.m3u",
                    "https://iptv-org.github.io/iptv/iptv3.m3u",
                )
            },
            mainViewModel = LeanbackMainViewModel(),
        )
    }
}