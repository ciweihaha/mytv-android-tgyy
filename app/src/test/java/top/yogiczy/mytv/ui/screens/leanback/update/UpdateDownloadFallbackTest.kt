package top.yogiczy.mytv.ui.screens.leanback.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.GitRelease
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.data.utils.HttpStatusException
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import java.io.File

class UpdateDownloadFallbackTest {
    private val directUrl =
        "https://github.com/ciweihaha/mytv-android-tgyy/releases/download/v2.2.15/mytv-android-2.2.15.apk"
    private val proxyUrl = Constants.GITHUB_PROXY + directUrl

    // downloadAndUpdate 里弹提示要读全局 Toast 单例：界面没跑起来时它还没
    // 初始化，单测先装一个（Unconfined 作用域，提示协程直接跑，不用等界面）
    private fun installToastState() {
        LeanbackToastState.I = LeanbackToastState(CoroutineScope(Dispatchers.Unconfined))
    }

    private fun setupViewModel(downloadUrl: String) = runBlocking {
        installToastState()
        LeanBackUpdateViewModel().also {
            it.checkUpdate("2.2.10") {
                GitRelease(version = "9.9.9", downloadUrl = downloadUrl, downloadSize = -1)
            }
        }
    }

    private fun tempFile() = File.createTempFile("update-test-", ".apk").apply { deleteOnExit() }

    @Test
    fun `proxy download fails then direct download succeeds`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                if (url == proxyUrl) throw RuntimeException("proxy down")
                File(path).writeText("apk")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)
        assertTrue(file.exists())
    }

    @Test
    fun `proxy download succeeding does not retry`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                File(path).writeText("apk")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl), calls)
    }

    @Test
    fun `both proxy and direct failing leaves no file`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, _, _ ->
                calls.add(url)
                throw RuntimeException("network down")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)
        assertFalse(file.exists())
    }

    @Test
    fun `non proxy download failing does not retry`() = runBlocking {
        val viewModel = setupViewModel(directUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, _, _ ->
                calls.add(url)
                throw RuntimeException("network down")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(directUrl), calls)
    }

    @Test
    fun `proxy 404 does not retry direct`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, _, _ ->
                calls.add(url)
                throw HttpStatusException(404, "下载文件失败：服务器返回 404，请检查下载链接")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl), calls)
        assertFalse(file.exists())
    }

    @Test
    fun `proxy 500 still retries direct`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                if (url == proxyUrl) throw HttpStatusException(
                    500,
                    "下载文件失败：服务器返回 500，请检查下载链接"
                )
                File(path).writeText("apk")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)
        assertTrue(file.exists())
    }

    @Test
    fun `cancelled proxy download does not retry direct and rethrows cancellation`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        // 假下载先把文件写出来再抛取消异常：真实下载是阻塞 I/O，
        // 取消时整包已经下完、withContext 在返回时才抛取消异常
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                File(path).writeText("apk")
                throw CancellationException("cancelled")
            }

        try {
            viewModel.downloadAndUpdate(file, fakeDownload)
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            assertEquals("cancelled", ex.message)
        }

        // 取消不是代理失败：不走直链重试、不记下载成功、不留残文件；
        // 没这三行守卫时这里会重试直链、再弹“下载更新失败”
        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl), calls)
        assertFalse(file.exists())
    }
}
