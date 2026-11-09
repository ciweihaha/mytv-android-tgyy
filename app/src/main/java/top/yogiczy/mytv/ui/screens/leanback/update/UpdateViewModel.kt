package top.yogiczy.mytv.ui.screens.leanback.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import top.yogiczy.mytv.data.entities.GitRelease
import top.yogiczy.mytv.data.repositories.git.GitRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastProperty
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.utils.Downloader
import top.yogiczy.mytv.utils.Logger
import top.yogiczy.mytv.utils.compareVersion
import java.io.File

class LeanBackUpdateViewModel : ViewModel() {
    private val log = Logger.create(javaClass.simpleName)

    private var _isChecking = false
    private var _isUpdating = false

    /** 正在检查更新：给设置页"应用更新"行显示"检查中" */
    private var _isCheckingUpdate by mutableStateOf(false)
    val isCheckingUpdate get() = _isCheckingUpdate

    /**
     * 最近一次检查是否成功拿到发行版信息：失败或从未检查时为 false，
     * 此时 latestRelease 还是 "0.0.0" 的默认值，不能当成"最新版本"显示
     */
    private var _checkCompleted by mutableStateOf(false)
    val checkCompleted get() = _checkCompleted

    private var _isUpdateAvailable by mutableStateOf(false)
    val isUpdateAvailable get() = _isUpdateAvailable

    private var _updateDownloaded by mutableStateOf(false)
    val updateDownloaded get() = _updateDownloaded

    private var _latestRelease by mutableStateOf(GitRelease())
    val latestRelease get() = _latestRelease

    var showDialog by mutableStateOf(false)

    suspend fun checkUpdate(currentVersion: String) {
        if (_isChecking) return
        if (_isUpdateAvailable) return

        try {
            _isChecking = true
            _isCheckingUpdate = true
            _latestRelease = GitRepository().latestRelease(Constants.GIT_RELEASE_LATEST_URL)
            _isUpdateAvailable = _latestRelease.version.compareVersion(currentVersion) > 0
            _checkCompleted = true
        } catch (e: Exception) {
            log.e("检查更新失败", e)
        } finally {
            _isChecking = false
            _isCheckingUpdate = false
        }
    }

    suspend fun downloadAndUpdate(latestFile: File) {
        if (!_isUpdateAvailable) return
        if (_isUpdating) return

        _isUpdating = true
        _updateDownloaded = false
        LeanbackToastState.I.showToast(
            "开始下载更新",
            LeanbackToastProperty.Duration.Custom(10_000),
        )

        try {
            Downloader.downloadTo(_latestRelease.downloadUrl, latestFile.path) {
                LeanbackToastState.I.showToast(
                    "正在下载更新: $it%",
                    LeanbackToastProperty.Duration.Custom(10_000),
                    "downloadProcess"
                )
            }

            // 分块传输等场景下连接干净断开也可能悄悄截断：按发行版里的
            // 文件大小校验，大小不对就当下载失败，不留残文件
            if (_latestRelease.downloadSize > 0 &&
                latestFile.length() != _latestRelease.downloadSize
            ) {
                throw Exception(
                    "下载文件不完整: 期望 ${_latestRelease.downloadSize} 字节，" +
                        "实际 ${latestFile.length()} 字节"
                )
            }

            _updateDownloaded = true
            LeanbackToastState.I.showToast("下载更新成功")
        } catch (ex: Exception) {
            log.e("下载更新失败", ex)
            // 残文件（下载中断 / 大小校验失败）不要留着，避免之后被误安装
            latestFile.delete()
            LeanbackToastState.I.showToast("下载更新失败")
        } finally {
            _isUpdating = false
        }
    }
}
