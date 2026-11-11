package top.yogiczy.mytv.ui.screens.leanback.settings.components

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.tv.material3.Icon
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackQrcodeDialog
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.utils.getPackageInfoCompat

@Composable
fun LeanbackSettingsCategoryAbout(
    modifier: Modifier = Modifier,
    packageInfo: PackageInfo = rememberPackageInfo(),
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "应用名称",
                trailingContent = Constants.APP_TITLE,
            )
        }

        item {
            LeanbackSettingsCategoryListItem(
                headlineContent = "应用版本",
                trailingContent = packageInfo.versionName.orEmpty(),
            )
        }

        item {
            var showQrDialog by remember { mutableStateOf(false) }

            LeanbackSettingsCategoryListItem(
                headlineContent = "代码仓库",
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.tv.material3.Text(Constants.APP_REPO)

                        Icon(
                            Icons.AutoMirrored.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                onSelected = { showQrDialog = true },
            )

            LeanbackQrcodeDialog(
                text = Constants.APP_REPO,
                description = "扫码前往代码仓库",
                showDialogProvider = { showQrDialog },
                onDismissRequest = { showQrDialog = false },
            )
        }
    }
}

// 包信息只查一次记下来：原来每次组合都调一次系统接口（跨进程调用），
// 界面一刷新就重新查一遍；版本号在运行中不会变，记下来就够了
@Composable
private fun rememberPackageInfo(context: Context = LocalContext.current): PackageInfo =
    remember(context) {
        context.packageManager.getPackageInfoCompat(context.packageName)
    }

@Preview
@Composable
private fun LeanbackSettingsAboutPreview() {
    LeanbackTheme {
        LeanbackSettingsCategoryAbout(
            packageInfo = PackageInfo().apply {
                versionName = "1.0.0"
            }
        )
    }
}
