package top.yogiczy.mytv.ui

import android.os.SystemClock
import androidx.annotation.IntRange
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackPadding
import top.yogiczy.mytv.ui.screens.leanback.main.LeanbackMainScreen
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastScreen
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState

@Composable
fun LeanbackApp(
    modifier: Modifier = Modifier,
    onBackPressed: () -> Unit = {},
) {
    val context = LocalContext.current
    val doubleBackPressedExitState = rememberLeanbackDoubleBackPressedExitState()

    LeanbackToastScreen()
    LeanbackMainScreen(
        modifier = modifier,
        onBackPressed = {
            if (doubleBackPressedExitState.allowExit) {
                onBackPressed()
            } else if (doubleBackPressedExitState.backPress()) {
                // backPress() 返回 false 表示这是同一次物理按键的重复
                // 投递：不提示、不做任何事，等真正第二次按压
                LeanbackToastState.I.showToast("再按一次退出")
            }
        },
    )
}


/**
 * 退出应用二次确认
 *
 * 2.2.12 真机问题：按一次 OK，「再按一次退出」一闪而过就直接退回
 * 桌面——一次物理按键在 HarmonyOS 智慧屏上会变成两次返回事件
 * （按下/抬起各合成一次，或同一事件重复投递），第一次把“可退出”
 * 置 true、第二次紧接着就退出，二次确认等于没生效。
 * 修法：两次按压之间必须间隔至少 [MIN_GAP_MILLIS] 才算两次——
 * 同一次物理按键拆出来的两起事件间隔只有几到几十毫秒，一律按
 * 同一次算，第二次直接忽略；人手双按最快也要 300ms 以上，不受
 * 影响。计时改用时间戳，不再走 Channel+debounce 协程：原来状态
 * 过期只靠挂在 LaunchedEffect 上的 observe() 重置，时间戳在每次
 * 读取时现算，不依赖任何协程生命周期。
 */
class LeanbackDoubleBackPressedExitState internal constructor(
    @IntRange(from = 0)
    private val resetSeconds: Int,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** 第一次按压的时间（uptime 毫秒），0 表示还没有按压过 */
    private var firstPressAt: Long = 0L

    /**
     * 是否在二次确认窗口内：距第一次按压已满最小间隔、且未超过
     * 确认窗口。窗口外或间隔内都返回 false。
     */
    val allowExit: Boolean
        get() {
            if (firstPressAt == 0L) return false
            val elapsed = clock() - firstPressAt
            return elapsed in minGapMillis until resetSeconds * 1000L
        }

    /**
     * 记录一次返回按压。返回 true 表示接受为一次新的按压（调用方
     * 应提示“再按一次退出”）；与上一次按压间隔小于最小间隔时视
     * 为同一物理按键的重复投递，返回 false，调用方什么都不做。
     */
    fun backPress(): Boolean {
        val now = clock()
        if (firstPressAt != 0L && now - firstPressAt < minGapMillis) return false
        firstPressAt = now
        return true
    }

    companion object {
        /** 两次按压之间的最小间隔（毫秒）：小于它按同一次算 */
        const val MIN_GAP_MILLIS = 300L
    }
}

/**
 * 退出应用二次确认状态
 */
@Composable
fun rememberLeanbackDoubleBackPressedExitState(@IntRange(from = 0) resetSeconds: Int = 2) =
    remember { LeanbackDoubleBackPressedExitState(resetSeconds = resetSeconds) }

val LeanbackParentPadding = PaddingValues(vertical = 12.dp, horizontal = 24.dp)

@Composable
fun rememberLeanbackChildPadding(direction: LayoutDirection = LocalLayoutDirection.current) =
    remember(direction) {
        LeanbackPadding(
            start = LeanbackParentPadding.calculateStartPadding(direction) + 8.dp,
            top = LeanbackParentPadding.calculateTopPadding(),
            end = LeanbackParentPadding.calculateEndPadding(direction) + 8.dp,
            bottom = LeanbackParentPadding.calculateBottomPadding()
        )
    }
