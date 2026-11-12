package top.yogiczy.mytv

import android.app.Application
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.CrashHandler
import top.yogiczy.mytv.utils.Logger
import top.yogiczy.mytv.utils.getPackageInfoCompat

class MyTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()

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
