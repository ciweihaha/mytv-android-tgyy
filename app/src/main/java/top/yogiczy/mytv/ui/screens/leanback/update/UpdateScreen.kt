package top.yogiczy.mytv.ui.screens.leanback.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.screens.leanback.update.components.LeanbackUpdateDialog
import top.yogiczy.mytv.utils.ApkInstaller
import top.yogiczy.mytv.utils.rememberPackageInfo
import java.io.File

@Composable
fun LeanbackUpdateScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
    updateViewModel: LeanBackUpdateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val packageInfo = rememberPackageInfo()
    val latestFile = remember { File(AppGlobal.cacheDir, "latest.apk") }

    LaunchedEffect(Unit) {
        delay(3000)
        updateViewModel.checkUpdate(packageInfo.versionName.orEmpty())

        val latestRelease = updateViewModel.latestRelease
        if (
            updateViewModel.isUpdateAvailable &&
            latestRelease.version != settingsViewModel.appLastLatestVersion
        ) {
            settingsViewModel.appLastLatestVersion = latestRelease.version

            if (settingsViewModel.updateForceRemind) {
                updateViewModel.showDialog = true
            } else {
                LeanbackToastState.I.showToast("新版本: v${latestRelease.version}")
            }
        }
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 从系统设置回来：有权限就直接装，没权限只提示，不再重复跳设置
            if (!tryInstallApk(context, latestFile.path)) {
                LeanbackToastState.I.showToast("未授予安装权限")
            }
        }

    LaunchedEffect(updateViewModel.updateDownloaded) {
        if (!updateViewModel.updateDownloaded) return@LaunchedEffect

        // 下载好了：有权限就直接装，没权限才跳系统设置去申请
        if (!tryInstallApk(context, latestFile.path)) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            launcher.launch(intent)
        }
    }

    LeanbackUpdateDialog(
        modifier = modifier,
        showDialogProvider = { updateViewModel.showDialog },
        onDismissRequest = { updateViewModel.showDialog = false },
        releaseProvider = { updateViewModel.latestRelease },
        onUpdateAndInstall = {
            updateViewModel.showDialog = false
            coroutineScope.launch(Dispatchers.IO) {
                updateViewModel.downloadAndUpdate(latestFile)
            }
        },
    )
}

/**
 * 有安装权限就直接装包，返回 true；没权限返回 false，由调用方决定是提示还是去申请。
 * 安卓 8.0 以下没有“未知来源安装”这个权限，直接装。
 * 更新页里两处原来各写了一份同样的判断（下载完装包、从设置回来再装），现在共用这一份。
 */
private fun tryInstallApk(context: Context, apkPath: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()
    ) {
        ApkInstaller.installApk(context, apkPath)
        return true
    }
    return false
}
