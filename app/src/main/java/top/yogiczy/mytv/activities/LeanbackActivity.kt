package top.yogiczy.mytv.activities

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import top.yogiczy.mytv.ui.LeanbackApp
import top.yogiczy.mytv.ui.screens.leanback.main.LeanbackMainViewModel
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.HttpServer
import top.yogiczy.mytv.ui.utils.LEANBACK_SELECT_KEY_CODES
import top.yogiczy.mytv.ui.utils.SP
import kotlin.system.exitProcess

class LeanbackActivity : ComponentActivity() {
    /**
     * 确定键兜底：Compose 层没消费掉的确定键（当前没有焦点、焦点在
     * SurfaceView 上等情况）不能再往下漏——2.2.11 只修了 Compose
     * 层，漏下去的确定键在 HarmonyOS 智慧屏上会被当成退出。
     * Compose 已处理的（super 返回 true）原样放行，正常按键不受影响
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (!handled && event.keyCode in LEANBACK_SELECT_KEY_CODES) {
            return true
        }
        return handled
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!SP.uiPipMode) return

        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        )
        super.onUserLeaveHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 记下本次启动时间：开机重试拉起时用它判断“这次开机后应用
        // 有没有起来过”，起来过就不重复拉起
        SP.appLastLaunchAt = System.currentTimeMillis()
        enableEdgeToEdge()
        setContent {
            // 隐藏状态栏、导航栏
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).let { insetsController ->
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            // 屏幕常亮
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            LeanbackTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    LeanbackApp(
                        onBackPressed = {
                            finish()
                            exitProcess(0)
                        },
                    )
                }
            }
        }

        // 与主界面同一 Activity 作用域的 ViewModel 实例（Compose 的 viewModel()
        // 取的也是这个 store），网页端改源后直接调它的 refresh() 立即重载
        val mainViewModel = ViewModelProvider(this)[LeanbackMainViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this)[LeanbackSettingsViewModel::class.java]

        HttpServer.start(
            applicationContext,
            showToast = {
                // HttpServer 回调跑在 IO 线程，可能在首帧合成前就触发（I 由
                // rememberLeanbackToastState 在合成提交后发布），未初始化时丢
                // 弃本次提示；直接读 lateinit 的 I 会抛
                // UninitializedPropertyAccessException 闪退
                if (LeanbackToastState.isInitialized) {
                    LeanbackToastState.I.showToast(it, id = "httpServer")
                }
            },
            onSettingsChanged = {
                mainViewModel.refresh()
            },
            onSettingsValuesChanged = {
                settingsViewModel.syncFromSP()
            },
        )
    }
}
