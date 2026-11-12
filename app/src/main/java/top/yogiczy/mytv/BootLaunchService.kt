package top.yogiczy.mytv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import top.yogiczy.mytv.activities.LeanbackActivity

/**
 * 开机拉起服务：收到 BOOT_COMPLETED 后由 BootReceiver 起这个前台服务，
 * 再由服务拉起主界面。Android 10+ 禁止后台直接 startActivity，带前台
 * 服务拉起是系统放行的路子（BOOT_COMPLETED 广播本身也是起前台服务
 * 的放行情形）；服务只在开机那一刻跑一下，拉起后立即自停，不常驻。
 */
class BootLaunchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "boot_launch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "开机自启",
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("正在启动天光云影")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        startActivity(Intent(this, LeanbackActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

        // 拉起请求已发出（startActivity 是同步 Binder 调用，系统受理后
        // 再停服务不影响拉起），立即收尾：去掉通知、停掉服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
