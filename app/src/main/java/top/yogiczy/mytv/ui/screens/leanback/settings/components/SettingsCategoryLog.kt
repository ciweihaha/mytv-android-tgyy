package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.collections.immutable.persistentListOf
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.utils.Logger
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun LeanbackSettingsCategoryLog(
    modifier: Modifier = Modifier,
    history: List<Logger.HistoryItem> = emptyList(),
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val historySorted = remember(history) {
        history.sortedByDescending { it.time }
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        // 按入库编号设 key：时间+标签+内容在同一毫秒里可能重复，
        // 两条一模一样的日志会撞键让列表直接崩掉
        items(historySorted, key = { it.id }) {
            // 有原因的日志把原因也显示出来：原来只显示 message，
            // 闪退报告（存在 cause 里的完整堆栈）只在局域网接口能看到，
            // 设置页上只能看到“上次启动发生闪退”、看不到崩在哪——
            // CrashHandler 的注释说两个地方都能看到，这里把它补上
            LeanbackSettingsCategoryListItem(
                headlineContent = "${it.level.toString()[0]} ${it.tag}",
                supportingContent = it.message +
                    (it.cause?.let { cause -> "\n$cause" } ?: ""),
                trailingContent = timeFormat.format(it.time),
            )
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsCategoryLogPreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryLog(
            history = persistentListOf(
                Logger.HistoryItem(
                    level = Logger.LevelType.ERROR,
                    tag = "LeanbackSettingsCategoryLog",
                    message = "This is a test message",
                    time = System.currentTimeMillis(),
                ),
            )
        )
    }
}
