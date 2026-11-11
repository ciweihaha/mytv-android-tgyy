package top.yogiczy.mytv.ui.screens.leanback.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastProperty.Companion.toMs
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Stable
class LeanbackToastState(private val coroutineScope: CoroutineScope) {
    private var _visible by mutableStateOf(false)
    val visible get() = _visible

    private var _current by mutableStateOf(LeanbackToastProperty())
    val current get() = _current

    private fun showToast(toast: LeanbackToastProperty) {
        // 请求时就领号：每条提示各起一个协程，先到的那条在 300ms 换场里
        // 睡着时，后到的提示可能已经显示了——旧提示醒来要先看号过期了
        // 没有，过期了就直接退出，别把最新提示盖回去（网页端与遥控操作
        // 可能同时触发，300ms 的窗口真实存在）
        val generation = toastGeneration.incrementAndGet()
        coroutineScope.launch {
            if (_visible && _current.id != toast.id) {
                _visible = false
                delay(300)
                if (generation != toastGeneration.get()) return@launch
            }

            _current = toast
            _visible = true
            channel.trySend(generation to toast.duration.toMs())
        }
    }

    fun showToast(
        message: String,
        duration: LeanbackToastProperty.Duration = LeanbackToastProperty.Duration.Default,
        id: String = UUID.randomUUID().toString(),
    ) {
        showToast(LeanbackToastProperty(message = message, duration = duration, id = id))
    }

    // Toast 换代计数：旧 Toast 的隐藏定时器可能与新 Toast 的显示撞在
    // 同一毫秒（旧 Toast 时长恰好等于新 Toast 请求时刻 + 300ms 换场），
    // 只关自己那一代的 Toast，新 Toast 不受影响。showToast 是多线程
    // 调的（界面与局域网回调），领号用原子计数，两个并发请求不会领到
    // 同一个号
    private val toastGeneration = AtomicInteger(0)
    private val channel = Channel<Pair<Int, Int>>(Channel.CONFLATED)

    @OptIn(FlowPreview::class)
    suspend fun observe() {
        channel.consumeAsFlow().debounce { it.second.toLong() }.collect { (generation, _) ->
            if (generation == toastGeneration.get()) _visible = false
        }
    }

    companion object {
        // I 只在首帧合成提交后由 SideEffect 发布（见
        // rememberLeanbackToastState）；合成中途赋值可能违反 Compose
        // 的规则，还会让外部回调拿到一个已被丢弃的 State
        lateinit var I: LeanbackToastState

        // I 只在首帧合成提交后发布；外部回调（HttpServer）可能在合成前
        // 触发，读之前先判这个。isInitialized 只能在声明处（这里）
        // 访问 backing field，类外直接用 ::I.isInitialized 编不过
        val isInitialized: Boolean get() = ::I.isInitialized
    }
}

@Composable
fun rememberLeanbackToastState(): LeanbackToastState {
    val coroutineScope = rememberCoroutineScope()

    val state = remember {
        LeanbackToastState(coroutineScope)
    }
    // 全局单例只在合成提交成功后发布：合成中途被丢弃时，外部回调
    // 不会拿到一个已被丢弃的 State；合成时直接赋值是 Compose 规则
    // 不提倡的副作用
    SideEffect { LeanbackToastState.I = state }
    LaunchedEffect(state) { state.observe() }
    return state
}

data class LeanbackToastProperty(
    val message: String = "",
    val duration: Duration = Duration.Default,
    val id: String = UUID.randomUUID().toString(),
) {
    sealed interface Duration {
        data object Default : Duration
        data class Custom(val duration: Int) : Duration
    }

    companion object {
        fun Duration.toMs(): Int = when (val it = this) {
            is Duration.Default -> 2300
            is Duration.Custom -> it.duration
        }
    }
}