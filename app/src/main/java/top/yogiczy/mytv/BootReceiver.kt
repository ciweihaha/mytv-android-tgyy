package top.yogiczy.mytv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.ContextCompat
import top.yogiczy.mytv.ui.utils.SP

/**
 * 开机自启动监听
 *
 * 2.2.11 只修了接收器导出与前台服务拉起，真机上还是起不来，原因在系统
 * 那边：华为智慧屏（HarmonyOS）默认不让第三方应用自启，智慧屏管家 →
 * 应用管理 → 应用启动管理里不开“允许自启动”，BOOT_COMPLETED 根本不
 * 会发到应用、拉起也会被拦。应用内开关只是第一道，系统那道必须用户
 * 在智慧屏管家亲手开——这一点在设置页的“开机自启”行也写明了。
 * 代码层面能做的补救都做在这里：
 * - 接收器不再挂 android:permission（系统侧转发广播的组件不一定持有
 *   RECEIVE_BOOT_COMPLETED，挂上反而可能收不到）；
 * - 除了 BOOT_COMPLETED，也接 QUICKBOOT_POWERON（华为快启广播）；
 * - 开机拉起一次后 90 秒再补一次：开机那一刻系统忙、拉起被拦或延迟
 *   是常事，补拉时先看这次开机后应用起来过没有，起来过就不重复拉。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON -> {
                if (!isBootLaunchEnabled(context)) return
                launchViaForegroundService(context)
                scheduleRetry(context)
            }

            ACTION_BOOT_LAUNCH_RETRY -> {
                if (!isBootLaunchEnabled(context)) return
                // 这次开机后应用起来过就不重复拉（用户手动打开过也算）
                if (hasLaunchedSinceBoot(context)) return
                launchViaForegroundService(context)
            }
        }
    }

    private fun isBootLaunchEnabled(context: Context): Boolean {
        val sp: SharedPreferences = SP.getInstance(context)
        return sp.getBoolean(SP.KEY.APP_BOOT_LAUNCH.name, true)
    }

    private fun launchViaForegroundService(context: Context) {
        // Android 10+ 后台直接 startActivity 会被系统拦截，改走
        // 前台服务拉起（BOOT_COMPLETED 广播本身就是起前台服务
        // 的放行情形）
        ContextCompat.startForegroundService(
            context,
            Intent(context, BootLaunchService::class.java),
        )
    }

    private fun scheduleRetry(context: Context) {
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_BOOT_LAUNCH_RETRY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 非精确闹钟即可（90 秒补拉不需要精确到秒），不用申请精确闹钟权限
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + RETRY_DELAY_MILLIS,
            pendingIntent,
        )
    }

    private fun hasLaunchedSinceBoot(context: Context): Boolean {
        val sp: SharedPreferences = SP.getInstance(context)
        val lastLaunchAt = sp.getLong(SP.KEY.APP_LAST_LAUNCH_AT.name, 0)
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return lastLaunchAt > bootAt
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_BOOT_LAUNCH_RETRY = "top.yogiczy.mytv.ACTION_BOOT_LAUNCH_RETRY"
        private const val RETRY_DELAY_MILLIS = 90_000L
    }
}
