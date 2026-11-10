package top.yogiczy.mytv.ui.screens.leanback.panel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun LeanbackPanelDateTime(
    modifier: Modifier = Modifier,
    timestamp: Long = rememberTimestamp(),
) {
    // 两个格式各建一个复用：本组件每秒重组一次（时间戳状态），
    // 写在函数体里每次重组都会新建（第 44 轮时间屏同类问题）
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("MM/dd EEE", Locale.getDefault()) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = dateFormat.format(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = timeFormat.format(timestamp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Preview
@Composable
private fun LeanbackPanelDateTimePreview() {
    LeanbackTheme {
        LeanbackPanelDateTime()
    }
}

@Composable
private fun rememberTimestamp(): Long {
    var timestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 只在前台才每秒刷新：面板开着按 Home 退后台后组合还在（没人
    // 会在退后台时关面板），原先的 LaunchedEffect(Unit) 会一直每秒
    // 跳一次、白白唤醒主线程；面板 15 秒无操作会自动关，但这 15 秒
    // 里时钟本来就看不见，回到前台时循环恢复、立刻重算一次时间
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                timestamp = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    return timestamp
}