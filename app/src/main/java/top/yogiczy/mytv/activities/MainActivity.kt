package top.yogiczy.mytv.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import top.yogiczy.mytv.ui.utils.SP

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityClass = when (SP.appDeviceDisplayType) {
            SP.AppDeviceDisplayType.LEANBACK -> LeanbackActivity::class.java
            SP.AppDeviceDisplayType.MOBILE -> MobileActivity::class.java
            SP.AppDeviceDisplayType.PAD -> PadActivity::class.java
        }

        // 跳板 Activity：直接起真正的界面，不要转场动画，否则启动时先
        // 闪一下再滑入，看着生硬
        startActivity(Intent(this, activityClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })

        finish()
    }
}
