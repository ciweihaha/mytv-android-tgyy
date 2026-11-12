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

    suspend fun checkUpdate(
        currentVersion: String,
        // 拉取最新发行版的方式：默认走线上更新源；单测传入假实现控制成功 / 失败，
        // 否则这条状态流转没法在不联网的单测里验
        fetchLatestRelease: suspend () -> GitRelease = {
            GitRepository().latestRelease(Constants.GIT_RELEASE_LATEST_URL)
        },
    ) {
        if (_isChecking) return
        // 注意：这里不能因为“已经知道有更新”就直接返回——电视常年不关机，
        // 启动时查到的只是当时的最新版，之后又发了新版时，只有重新联网查
        // 才能拿到；直接返回会让“应用更新”行永远只给旧版，直到重启应用。
        // 查失败时旧值不动（下面 catch 不改 _latestRelease / _isUpdateAvailable），
        // 所以重查失败也不会丢掉已经知道的那个更新。

        try {
            _isChecking = true
            _isCheckingUpdate = true
            // 新一轮检查开始就把旧的成功标记清掉：checkCompleted 的意思是
            // “最近一次检查是否成功拿到发行版信息”，失败或从未检查时为
            // false——原先只在成功时置 true、从不复位，成功过一次后手动
            // 重查失败也还留着 true，设置页“应用更新”行继续显示“已是最新
            // 版本”，点按重查失败的提示也是“已是最新版本”而不是“检查更新失败”
            _checkCompleted = false
            _latestRelease = fetchLatestRelease()
            _isUpdateAvailable = _latestRelease.version.compareVersion(currentVersion) > 0
            _checkCompleted = true
        } catch (e: Exception) {
            log.e("检查更新失败", e)
        } finally {
            _isChecking = false
            _isCheckingUpdate = false
        }
    }

    suspend fun downloadAndUpdate(
        latestFile: File,
        // 下安装包的方式：默认走 Downloader；单测传入假实现控制成功 / 失败，
        // 否则代理失败改走直链重试这条路没法在不联网的单测里验
        downloadFile: suspend (String, String, ((Int) -> Unit)?) -> Unit = Downloader::downloadTo,
    ) {
        if (!_isUpdateAvailable) return
        if (_isUpdating) return

        _isUpdating = true
        _updateDownloaded = false
        LeanbackToastState.I.showToast(
            "开始下载更新",
            LeanbackToastProperty.Duration.Custom(10_000),
        )

        try {
            try {
                downloadOnce(_latestRelease.downloadUrl, latestFile, downloadFile)
            } catch (ex: Exception) {
                // 代理挂了改走 GitHub 直链再试一次：mirror.ghproxy.com 实测
                // 只接不答（TCP+TLS 都通、HTTP 一直没回包），直连 GitHub
                // 能通；没走代理的更新源（Gitee）没有第二条路，直接走失败
                val directUrl = _latestRelease.downloadUrl.removePrefix(Constants.GITHUB_PROXY)
                if (directUrl == _latestRelease.downloadUrl) throw ex
                log.w("代理下载失败，改走 GitHub 直链重试", ex)
                latestFile.delete()
                downloadOnce(directUrl, latestFile, downloadFile)
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

    /**
     * 下一次安装包并校验大小：代理与直链两条路共用。
     */
    private suspend fun downloadOnce(
        url: String,
        latestFile: File,
        downloadFile: suspend (String, String, ((Int) -> Unit)?) -> Unit,
    ) {
        downloadFile(url, latestFile.path) {
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
    }
}
