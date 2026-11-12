package top.yogiczy.mytv.utils

import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闪退捕获：应用在真机上闪退时，系统只弹一个“应用已停止”、不留任何
 * 可查的信息，之后只能靠猜。2.2.13 平板上“点哪都闪退”就是这么查不
 * 到根因的——代码审计把交互路径翻了个遍，没找到确定的崩溃点。
 *
 * 现在装一个全局未捕获异常处理器：任何线程（主线程、协程、播放器
 * 回调）抛出的未捕获异常，先把完整堆栈写进缓存目录的
 * crash-last.log，再交给原来的处理器（系统照常杀进程、弹提示，
 * 崩溃行为和原来一模一样）。下次启动时读回这份报告、记进日志历史
 * （设置 → 日志、局域网日志接口都能看到），就知道上次到底崩在哪。
 */
object CrashHandler {
    const val CRASH_LOG_FILE_NAME = "crash-last.log"

    /** 单份报告上限：堆栈再长也只留前 16KB，日志历史里一条占位即可 */
    private const val MAX_REPORT_CHARS = 16_000

    @Volatile
    private var installed = false

    fun install(cacheDir: File, versionName: String) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = formatCrashReport(
                    threadName = thread.name,
                    throwable = throwable,
                    timestamp = System.currentTimeMillis(),
                    appVersion = versionName,
                    androidVersion = Build.VERSION.RELEASE ?: "?",
                    androidSdk = Build.VERSION.SDK_INT,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                )
                File(cacheDir, CRASH_LOG_FILE_NAME).writeText(report)
            } catch (_: Exception) {
                // 写日志本身失败时不做任何事：崩溃处理里再抛异常会掩盖原始崩溃
            }
            // 交给原来的处理器（系统默认会杀进程、弹崩溃提示），行为和原来一致
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 读回上次启动的闪退报告：读完就删掉文件，只汇报一次，下次启动
     * 不再重复。没有报告、读失败、文件是空的都返回 null。
     */
    fun readPreviousCrashReport(cacheDir: File): String? {
        val file = File(cacheDir, CRASH_LOG_FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            try {
                file.delete()
            } catch (_: Exception) {
                // 删不掉也无妨：下次启动会再读一次，不影响使用
            }
        }
    }

    /**
     * 闪退报告文本：时间、版本、设备、系统、线程、异常类型与完整堆栈。
     * 纯函数（时间与设备信息都由调用方传进来），方便单元测试直接调用。
     */
    internal fun formatCrashReport(
        threadName: String,
        throwable: Throwable,
        timestamp: Long,
        appVersion: String,
        androidVersion: String,
        androidSdk: Int,
        deviceModel: String,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
        val stackTrace = StringWriter()
            .also { throwable.printStackTrace(PrintWriter(it)) }
            .toString()
        return buildString {
            appendLine("天光云影闪退报告")
            appendLine("时间：$time")
            appendLine("版本：$appVersion")
            appendLine("设备：$deviceModel")
            appendLine("系统：Android $androidVersion (SDK $androidSdk)")
            appendLine("线程：$threadName")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine("堆栈：")
            append(stackTrace)
        }.take(MAX_REPORT_CHARS)
    }
}
