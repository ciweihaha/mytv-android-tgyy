package top.yogiczy.mytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import top.yogiczy.mytv.ui.utils.SP

/**
 * 开机自启动监听
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val sp: SharedPreferences = SP.getInstance(context)
            val bootLaunch = sp.getBoolean(SP.KEY.APP_BOOT_LAUNCH.name, true)

            if (bootLaunch) {
                // Android 10+ 后台直接 startActivity 会被系统拦截，改走
                // 前台服务拉起（BOOT_COMPLETED 广播本身就是起前台服务
                // 的放行情形）；接收器本身要在 Manifest 里 exported，
                // 否则系统广播根本发不进来
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BootLaunchService::class.java),
                )
            }
        }
    }
}
