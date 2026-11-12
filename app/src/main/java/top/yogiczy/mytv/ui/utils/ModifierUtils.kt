package top.yogiczy.mytv.ui.utils

import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.utils.Logger
import kotlin.math.absoluteValue

private val inputLog = Logger.create("LeanbackInput")

/**
 * 交互回调兜底：遥控按键、触摸点按、滑动手势的回调里抛异常时记一条
 * 日志（设置 → 日志可看），不让异常扩散出去崩掉整个应用——电视/
 * 平板上一次点击或按键不该因为回调里的偶发异常就闪退。只接
 * Exception，Error（内存溢出等）照常抛出，不掩盖真正致命的问题。
 * 普通函数（不 inline）：inline 函数体不能访问文件私有的 inputLog。
 * 顶层函数方便单元测试直接调用。
 */
internal fun safeLeanbackInvoke(action: () -> Unit) {
    try {
        action()
    } catch (e: Exception) {
        inputLog.e("交互回调异常", e)
    }
}

/**
 * 确定键：遥控器中间 OK 键与键盘回车。确定键不移动焦点，消费按下
 * 不会影响焦点移动；见 [handleLeanbackKeyEvents] 按下消费的说明
 */
internal val LEANBACK_SELECT_KEY_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
)

/**
 * 确定键按下是否应该消费（纯函数，方便单元测试）：确定键在点按表
 * 里（调用方注册了 onSelect）才消费；方向键、菜单键等不在此列——
 * 方向键的焦点移动靠框架在按下时处理，消费掉会让列表焦点挪不动
 */
internal fun shouldConsumeLeanbackSelectKeyDown(
    keyCode: Int,
    onKeyTap: Map<Int, () -> Unit>,
): Boolean = keyCode in LEANBACK_SELECT_KEY_CODES && onKeyTap.containsKey(keyCode)

@Composable
fun Modifier.handleLeanbackKeyEvents(
    onKeyTap: Map<Int, () -> Unit> = emptyMap(),
    onKeyLongTap: Map<Int, () -> Unit> = emptyMap(),
): Modifier {
    // 按下状态必须跨重组保留：原先是函数体内的局部 map，每次重组都会新建，
    // 若在 ACTION_DOWN 与 ACTION_UP 之间发生重组，抬起时查不到按下记录，
    // 本次按键就丢了（遥控偶尔"没反应"）
    val keyDownMap = remember { mutableMapOf<Int, Boolean>() }

    return onPreviewKeyEvent {
        when (it.nativeKeyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                if (it.nativeKeyEvent.repeatCount == 0) {
                    keyDownMap[it.nativeKeyEvent.keyCode] = true
                    // 确定键在按下时就消费掉：2.2.11 只在抬起时消费，
                    // 一旦焦点在按下抬起之间挪走（面板开了又关、焦点
                    // 根本不在视频层），抬起就收不到、事件直接漏到
                    // Activity/系统，HarmonyOS 智慧屏上会被当成退出。
                    // 2.2.11 在真机上仍退出，就是这条漏路没堵住
                    shouldConsumeLeanbackSelectKeyDown(
                        it.nativeKeyEvent.keyCode,
                        onKeyTap,
                    )
                } else if (it.nativeKeyEvent.repeatCount == 1) {
                    keyDownMap.remove(it.nativeKeyEvent.keyCode)
                    // 长按在按下重复时触发一次，消费掉：防止松开时又触发一次点按
                    val longTap = onKeyLongTap[it.nativeKeyEvent.keyCode]
                    if (longTap != null) {
                        safeLeanbackInvoke(longTap)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }

            KeyEvent.ACTION_UP -> {
                if (keyDownMap[it.nativeKeyEvent.keyCode] == true) {
                    keyDownMap.remove(it.nativeKeyEvent.keyCode)
                    // 点按在这里触发，消费掉：已处理的键不再往下漏到
                    // Activity/系统兜底处理——原来这里永远返回 false，
                    // 处理过的确定键一路漏下去，荣耀电视上会被系统当成
                    // 返回、直接退出应用。回调抛异常也照样消费（见
                    // safeLeanbackInvoke），不让它漏下去被当成退出
                    val tap = onKeyTap[it.nativeKeyEvent.keyCode]
                    if (tap != null) {
                        safeLeanbackInvoke(tap)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }

            else -> false
        }
    }
}

/**
 * 遥控按键“点按”映射表（纯函数，方便单元测试）：
 * 只收录调用方真正传了回调的键——没传的键不进表，事件继续往下传给
 * 获得焦点的子项或框架处理。原来这里把全部键都收进表、回调默认空
 * 函数，事件到哪一级都算“处理过”，却又不消费，等于白白占了表。
 */
internal fun leanbackKeyTapMap(
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onLongSelect: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onNumber: ((Int) -> Unit)? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Map<Int, () -> Unit> = buildMap {
    onLeft?.let {
        put(KeyEvent.KEYCODE_DPAD_LEFT, it)
        if (sdkInt >= Build.VERSION_CODES.N_MR1) {
            put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, it)
        }
    }
    onRight?.let {
        put(KeyEvent.KEYCODE_DPAD_RIGHT, it)
        if (sdkInt >= Build.VERSION_CODES.N_MR1) {
            put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT, it)
        }
    }
    onUp?.let {
        put(KeyEvent.KEYCODE_DPAD_UP, it)
        put(KeyEvent.KEYCODE_CHANNEL_UP, it)
        if (sdkInt >= Build.VERSION_CODES.N_MR1) {
            put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, it)
        }
    }
    onDown?.let {
        put(KeyEvent.KEYCODE_DPAD_DOWN, it)
        put(KeyEvent.KEYCODE_CHANNEL_DOWN, it)
        if (sdkInt >= Build.VERSION_CODES.N_MR1) {
            put(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN, it)
        }
    }
    onSelect?.let {
        put(KeyEvent.KEYCODE_DPAD_CENTER, it)
        put(KeyEvent.KEYCODE_ENTER, it)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, it)
    }
    onSettings?.let {
        put(KeyEvent.KEYCODE_MENU, it)
        put(KeyEvent.KEYCODE_SETTINGS, it)
        put(KeyEvent.KEYCODE_HELP, it)
        put(KeyEvent.KEYCODE_H, it)
    }
    // 键盘 L 键当长按确定用（原有行为保留）
    onLongSelect?.let { put(KeyEvent.KEYCODE_L, it) }
    onNumber?.let { number ->
        for (i in 0..9) {
            put(KeyEvent.KEYCODE_0 + i) { number(i) }
        }
    }
}

/**
 * 遥控按键“长按”映射表（纯函数，方便单元测试）：同样只收录真正传了
 * 回调的键。
 */
internal fun leanbackKeyLongTapMap(
    onLongLeft: (() -> Unit)? = null,
    onLongRight: (() -> Unit)? = null,
    onLongUp: (() -> Unit)? = null,
    onLongDown: (() -> Unit)? = null,
    onLongSelect: (() -> Unit)? = null,
): Map<Int, () -> Unit> = buildMap {
    onLongLeft?.let { put(KeyEvent.KEYCODE_DPAD_LEFT, it) }
    onLongRight?.let { put(KeyEvent.KEYCODE_DPAD_RIGHT, it) }
    onLongUp?.let {
        put(KeyEvent.KEYCODE_DPAD_UP, it)
        put(KeyEvent.KEYCODE_CHANNEL_UP, it)
    }
    onLongDown?.let {
        put(KeyEvent.KEYCODE_DPAD_DOWN, it)
        put(KeyEvent.KEYCODE_CHANNEL_DOWN, it)
    }
    onLongSelect?.let {
        put(KeyEvent.KEYCODE_ENTER, it)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, it)
        put(KeyEvent.KEYCODE_DPAD_CENTER, it)
    }
}

fun Modifier.handleLeanbackDragGestures(
    onSwipeUp: () -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
): Modifier {
    val speedThreshold = 100.dp
    val distanceThreshold = 10.dp

    val verticalTracker = VelocityTracker()
    var verticalDragOffset = 0f
    val horizontalTracker = VelocityTracker()
    var horizontalDragOffset = 0f


    return this then pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = {
                verticalDragOffset = 0f
                verticalTracker.resetTracking()
            },
            onDragEnd = {
                if (verticalDragOffset.absoluteValue > distanceThreshold.toPx()) {
                    // 速度只算一次：原来先比正向、反向时又算一次，同一手势
                    // 结束时两次算出来的值必然相同；速度阈值换算也一次算好共用
                    val velocityY = verticalTracker.calculateVelocity().y
                    val speedPx = speedThreshold.toPx()
                    if (velocityY > speedPx) {
                        safeLeanbackInvoke(onSwipeDown)
                    } else if (velocityY < -speedPx) {
                        safeLeanbackInvoke(onSwipeUp)
                    }
                }
            },
        ) { change, dragAmount ->
            verticalDragOffset += dragAmount
            verticalTracker.addPosition(change.uptimeMillis, change.position)
        }
    }.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = {
                horizontalDragOffset = 0f
                horizontalTracker.resetTracking()
            },
            onDragEnd = {
                if (horizontalDragOffset.absoluteValue > distanceThreshold.toPx()) {
                    // 同上：速度与阈值换算各只算一次记下来共用
                    val velocityX = horizontalTracker.calculateVelocity().x
                    val speedPx = speedThreshold.toPx()
                    if (velocityX > speedPx) {
                        safeLeanbackInvoke(onSwipeRight)
                    } else if (velocityX < -speedPx) {
                        safeLeanbackInvoke(onSwipeLeft)
                    }
                }
            },
        ) { change, dragAmount ->
            horizontalDragOffset += dragAmount
            horizontalTracker.addPosition(change.uptimeMillis, change.position)
        }
    }
}

@Composable
fun Modifier.handleLeanbackKeyEvents(
    key: Any = Unit,
    onLeft: (() -> Unit)? = null,
    onLongLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onLongRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onLongUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onLongDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
    onLongSelect: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onNumber: ((Int) -> Unit)? = null,
) = this then handleLeanbackKeyEvents(
    // 回调默认是 null 而不是空函数：没传的键不进映射表，事件继续往下传，
    // 不会占住里层获得焦点的子项的键（见 leanbackKeyTapMap）
    onKeyTap = leanbackKeyTapMap(
        onLeft = onLeft,
        onRight = onRight,
        onUp = onUp,
        onDown = onDown,
        onSelect = onSelect,
        onLongSelect = onLongSelect,
        onSettings = onSettings,
        onNumber = onNumber,
    ),
    onKeyLongTap = leanbackKeyLongTapMap(
        onLongLeft = onLongLeft,
        onLongRight = onLongRight,
        onLongUp = onLongUp,
        onLongDown = onLongDown,
        onLongSelect = onLongSelect,
    ),
).pointerInput(key) {
    detectTapGestures(
        onTap = { safeLeanbackInvoke { onSelect?.invoke() } },
        onLongPress = { safeLeanbackInvoke { onLongSelect?.invoke() } },
        onDoubleTap = { safeLeanbackInvoke { onSettings?.invoke() } },
    )
}

/**
 * 任意用户操作都续期一次（如快捷面板自动关闭计时）：按键、触摸、滑动、
 * 双击、长按都走这里。回调同样包 safeLeanbackInvoke 兜底——上面几个
 * 交互处理都包了，这里是漏网的一处；回调里偶发异常不该让整个应用闪退。
 */
fun Modifier.handleLeanbackUserAction(onHandle: () -> Unit) =
    onPreviewKeyEvent { safeLeanbackInvoke(onHandle); false }
        .pointerInput(Unit) { detectDragGestures { _, _ -> safeLeanbackInvoke(onHandle) } }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { safeLeanbackInvoke(onHandle) },
                onDoubleTap = { safeLeanbackInvoke(onHandle) },
                onLongPress = { safeLeanbackInvoke(onHandle) },
                onPress = { safeLeanbackInvoke(onHandle) },
            )
        }