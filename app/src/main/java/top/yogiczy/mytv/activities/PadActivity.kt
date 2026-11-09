package top.yogiczy.mytv.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yogiczy.mytv.ui.theme.PadTheme
import top.yogiczy.mytv.ui.utils.SP

class PadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text = "平板端暂未开放")
                        Spacer(modifier = Modifier.height(16.dp))
                        // 占位屏不能是死胡同：万一显示模式被切到这里，
                        // 点一下就能回 TV 模式，而不是只能清数据
                        Button(onClick = {
                            SP.appDeviceDisplayType = SP.AppDeviceDisplayType.LEANBACK
                            startActivity(
                                Intent(this@PadActivity, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                }
                            )
                            finish()
                        }) {
                            Text(text = "返回 TV 模式")
                        }
                    }
                }
            }
        }
    }
}
