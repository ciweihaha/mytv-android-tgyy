package top.yogiczy.mytv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.CrashHandler
import top.yogiczy.mytv.utils.Logger
import top.yogiczy.mytv.utils.getPackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上次崩溃的报告文件名：只保留最近一次，写在缓存目录里，局域网
 * 服务 /api/crash 读的就是它。
 */
internal const val CRASH_REPORT_FILE_NAME = "last_crash.txt"

/**
 * 崩溃报告正文（纯函数，方便单元测试）：头四行固定字段，堆栈原样附后。
 */
internal fun buildCrashReport(
    time: String,
    threadName: String,
    appVersion: String,
    device: String,
    stackTrace: String,
): String = buildString {
    appendLine("time: $time")
    appendLine("thread: $threadName")
    appendLine("version: $appVersion")
    appendLine("device: $device")
    append(stackTrace)
}

/**
 * 默认未捕获异常处理：崩溃堆栈先落盘，再链回原来的 handler。
 * 没它，OK 键闪退只能靠猜——静态排查查不到的崩溃必须有堆栈才能定位。
 * 必须在 onCreate 最先安装：初始化过程里崩了也要有记录。
 * 处理里任何一步抛错都吞掉：崩溃处理里再崩，进程直接没了还不留痕。
 */
internal fun installCrashHandler(context: Context) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val version = try {
                context.packageManager.getPackageInfoCompat(context.packageName)
                    .versionName.orEmpty()
            } catch (_: Exception) {
                "unknown"
            }
            val report = buildCrashReport(
                time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date()),
                threadName = thread.name,
                appVersion = version,
                device = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
                stackTrace = throwable.stackTraceToString(),
            )
            File(context.cacheDir, CRASH_REPORT_FILE_NAME).writeText(report)
        } catch (_: Exception) {
            // 落盘失败也别挡着原来的崩溃流程
        }
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable)
        } else {
            // 理论上系统一定装了默认 handler；万一没有，主动杀进程，
            // 否则主线程死了进程还在，界面会僵住
            Process.killProcess(Process.myPid())
        }
    }
}

class MyTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        installCrashHandler(applicationContext)
        AppGlobal.cacheDir = applicationContext.cacheDir
        SP.init(applicationContext)

        // 闪退捕获先装上：任何未捕获异常都把堆栈写进 crash-last.log；
        // 再读上次启动留下的报告（有的话），记进日志历史——设置里
        // 的日志页和局域网日志接口都能看到，闪退不再无从查起
        val versionName = try {
            packageManager.getPackageInfoCompat(packageName).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        CrashHandler.install(AppGlobal.cacheDir, versionName)
        CrashHandler.readPreviousCrashReport(AppGlobal.cacheDir)?.let { report ->
            Logger.addHistoryItem(
                Logger.HistoryItem(
                    level = Logger.LevelType.ERROR,
                    tag = "CrashHandler",
                    message = "上次启动发生闪退",
                    cause = report,
                )
            )
        }
    }
}
