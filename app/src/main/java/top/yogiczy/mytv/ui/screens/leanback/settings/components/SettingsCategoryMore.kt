package top.yogiczy.mytv.ui.screens.leanback.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import kotlinx.coroutines.delay
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackQrcode
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.HttpServer

@Composable
fun LeanbackSettingsCategoryMore(
    modifier: Modifier = Modifier,
    serverUrl: String = HttpServer.serverUrl,
    accessCode: String = HttpServer.accessCode,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "设置页面",
                trailingContent = serverUrl,
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "网页端访问码",
                supportingContent = "修改设置与上传安装包时需要输入，应用重启后更换",
                trailingContent = accessCode,
            )
        }

        item {
            var show by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                delay(100)
                show = true
            }

            if (show) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    LeanbackQrcode(
                        modifier = Modifier
                            .width(200.dp)
                            .height(200.dp),
                        text = serverUrl,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun LeanbackSettingsMorePreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryMore(
            serverUrl = "http://127.0.0.1:10481",
            accessCode = "123456",
        )
    }
}
