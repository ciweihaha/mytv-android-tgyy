package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsCategories
import top.yogiczy.mytv.utils.Logger

@Composable
fun LeanbackSettingsCategoryContent(
    modifier: Modifier = Modifier,
    focusedCategoryProvider: () -> LeanbackSettingsCategories = { LeanbackSettingsCategories.entries.first() },
) {
    val focusedCategory = focusedCategoryProvider()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = focusedCategory.title, style = MaterialTheme.typography.headlineSmall)

        when (focusedCategory) {
            LeanbackSettingsCategories.ABOUT -> LeanbackSettingsCategoryAbout()
            LeanbackSettingsCategories.APP -> LeanbackSettingsCategoryApp()
            LeanbackSettingsCategories.IPTV -> LeanbackSettingsCategoryIptv()
            LeanbackSettingsCategories.EPG -> LeanbackSettingsCategoryEpg()
            LeanbackSettingsCategories.UI -> LeanbackSettingsCategoryUI()
            LeanbackSettingsCategories.FAVORITE -> LeanbackSettingsCategoryFavorite()
            LeanbackSettingsCategories.UPDATE -> LeanbackSettingsCategoryUpdate()
            LeanbackSettingsCategories.VIDEO_PLAYER -> LeanbackSettingsCategoryVideoPlayer()
            LeanbackSettingsCategories.HTTP -> LeanbackSettingsCategoryHttp()
            LeanbackSettingsCategories.DEBUG -> LeanbackSettingsCategoryDebug()
            LeanbackSettingsCategories.LOG -> {
                // 日志页只在切到这页时订阅：新日志进来列表自己刷新，
                // 原来是打开设置时读一次快照，停在日志页上新日志一直不出现
                val logHistory by Logger.historyFlow.collectAsStateWithLifecycle()
                LeanbackSettingsCategoryLog(
                    history = logHistory,
                )
            }
            LeanbackSettingsCategories.MORE -> LeanbackSettingsCategoryMore()
        }
    }
}