package top.yogiczy.mytv.ui.screens.leanback.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.SP
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 整点 / 半点模式共用的“靠近周期起点吗”判断：原来两个分支各写了一份
 * 一模一样的取余比较，只差周期（1 小时 / 半小时）；以后改显示窗口
 * 容易只改一处、两边走样。现在只留这一份，两处只传周期。
 * 窗口：周期开始后 31 秒内（30 秒显示范围 + 每秒刷新 1 秒容差），
 * 或周期结束前 30 秒内。
 */
internal fun isNearPeriodBoundary(timestamp: Long, periodMs: Long): Boolean {
    // 取余要按本地墙上时间算：时间戳是 UTC，整点/半点是本地时间；
    // 半小时时区（印度 +5:30、伊朗 +3:30、尼泊尔 +5:45 等）下直接
    // 拿 UTC 时间戳取余，窗口整个错开半小时/45 分钟——当地整点时不
    // 显示，半点时反而显示。加上此时刻的本地偏移（含夏令时）再取余，
    // 窗口就落在本地整点/半点上；整小时时区偏移是周期的整倍数，结果
    // 和原来一模一样。floorMod：1970 年之前的时间戳加偏移仍为负时，
    // 普通 % 会出负余数
    val remainder = Math.floorMod(
        timestamp + TimeZone.getDefault().getOffset(timestamp), periodMs
    )
    return remainder <= Constants.UI_TIME_SHOW_RANGE + 1000 ||
        remainder >= periodMs - Constants.UI_TIME_SHOW_RANGE
}

@Composable
fun LeanbackPanelDateTimeScreen(
    modifier: Modifier = Modifier,
    showModeProvider: () -> SP.UiTimeShowMode = { SP.UiTimeShowMode.HIDDEN },
) {
    val childPadding = rememberLeanbackChildPadding()
    // 每秒创建一次 SimpleDateFormat 是纯浪费：两个格式各建一个复用
    //（只在这一条协程里用，不存在 SimpleDateFormat 的线程安全问题）
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeWithSecondsFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    var timeText by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        // 只在前台可见时刷新时钟：这个组件常驻在主界面里（MainContent
        // 组装），原先的 LaunchedEffect(Unit) 退到后台也每秒跳一次、
        // 每秒刷新一次界面，白白唤醒主线程；退后台时时钟本来就看
        // 不见，回到前台时循环恢复、立刻重算一次显示的时间
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val timestamp = System.currentTimeMillis()

                // 显示模式每秒只读一次：原来算“现在要不要显示”先读一次、
                // 选时间格式又读一次，每秒读两次设置；整点/半整点模式下
                // 取余也各算了两遍。一次读好、后面两处共用，同一秒内
                // “显示与否”和“用什么格式”也不会读到两个不同的模式
                val showMode = showModeProvider()

                visible = when (showMode) {
                    SP.UiTimeShowMode.HIDDEN -> false
                    SP.UiTimeShowMode.ALWAYS -> true

                    SP.UiTimeShowMode.EVERY_HOUR ->
                        isNearPeriodBoundary(timestamp, 3_600_000)

                    SP.UiTimeShowMode.HALF_HOUR ->
                        isNearPeriodBoundary(timestamp, 1_800_000)
                }

                if (visible) {
                    timeText = when (showMode) {
                        SP.UiTimeShowMode.ALWAYS -> timeFormat
                        else -> timeWithSecondsFormat
                    }.format(timestamp)
                }

                delay(1000)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (visible) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = childPadding.top, end = childPadding.end)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(0.8f),
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackPanelDateTimeScreenPreview() {
    LeanbackTheme {
        LeanbackPanelDateTimeScreen(showModeProvider = { SP.UiTimeShowMode.ALWAYS })
    }
}