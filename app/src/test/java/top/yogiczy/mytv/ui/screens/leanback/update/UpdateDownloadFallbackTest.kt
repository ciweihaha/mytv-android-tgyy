package top.yogiczy.mytv.ui.screens.leanback.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

    private fun setupViewModel(downloadUrl: String, downloadSize: Long = -1) = runBlocking {
        installToastState()
        LeanBackUpdateViewModel().also {
            it.checkUpdate("2.2.10") {
                GitRelease(version = "9.9.9", downloadUrl = downloadUrl, downloadSize = downloadSize)
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
    fun `size mismatch fails the download and deletes the partial file`() = runBlocking {
        // 发行版里写明 100 字节、实际只下到 3 字节：分块传输干净断开也可能
        // 悄悄截断，大小不对就当下载失败，不留残文件、不能装
        val viewModel = setupViewModel(proxyUrl, downloadSize = 100)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                File(path).writeText("apk")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        // 大小不对不是 404，走直链再试一次也一样截断，两次都失败才收尾
        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)
        assertFalse(file.exists())
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

    @Test
    fun `cancelled download resets updating flag so retry works`() = runBlocking {
        // 第一轮下载卡在假下载里、从外面取消；第二轮下应能正常下。
        // 1865 轮只钉死了失败路径的 finally 复位，取消路径一次也
        // 没测过——复位若只写在成功/失败分支里、取消分支漏了，第
        // 二次调用会被重入守卫直接拦掉，更新页从此再也下不了包；
        // 壳线 772 轮同款用例断言了取消后状态复位（壳线把 isUpdating
        // 对外暴露了），天光这边 _isUpdating 是私有的，只能用
        // “取消后真的能再下”来断言同一件事
        val viewModel = setupViewModel(proxyUrl)
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val file = tempFile()
        var attempts = 0
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                attempts++
                if (attempts == 1) {
                    started.complete(Unit)
                    releaseGate.await()
                }
                File(path).writeText("apk")
            }

        val first = async { viewModel.downloadAndUpdate(file, fakeDownload) }
        started.await()
        first.cancel()
        try {
            first.await()
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            // 预期
        }
        assertFalse(viewModel.updateDownloaded)

        viewModel.downloadAndUpdate(file, fakeDownload)
        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, proxyUrl), calls)
        assertTrue(file.exists())
    }

    @Test
    fun `下载进行中再次下载直接返回不重复下载`() = runBlocking {
        val viewModel = setupViewModel(proxyUrl)
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val file = tempFile()
        // 只有第一次下载卡在门闩上：没这行守卫时第二次调用也会进
        // 假下载、同样卡住，测试会直接挂起而不是失败，所以第二次
        // 调用走不卡的那条路——守卫一丢，这里就会多一次下载调用
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                if (calls.size == 1) {
                    started.complete(Unit)
                    releaseGate.await()
                }
                File(path).writeText("apk")
            }

        // 第一轮下载卡在假下载里：_isUpdating 已置 true
        val first = async { viewModel.downloadAndUpdate(file, fakeDownload) }
        started.await()

        // 正在下时再调一次：应直接返回，第二次下载一次也不调——
        // 这档之前没有任何单测（旧用例全是下完再下，_isUpdating
        // 那行守卫一次也没走通过）；没这行守卫时两次下载会并行跑，
        // 同一份安装包写两次、提示也弹两次
        viewModel.downloadAndUpdate(file, fakeDownload)
        assertEquals(listOf(proxyUrl), calls)

        releaseGate.complete(Unit)
        first.await()
        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl), calls)
        assertTrue(file.exists())
    }

    @Test
    fun `successful download then failed download clears downloaded flag`() = runBlocking {
        // 第一次下成功、第二次下代理与直链都失败：每次下载开始
        // 先把成功标记清掉（第 97 行）没有任何单测——旧用例全是
        // 同一台 ViewModel 只下一次；没这句时第二次失败后标记还
        // 留着 true，设置页会一直显示“已下载”，旧单测发现不了
        // （checkUpdate 那边同款复位已有 failed recheck 用例钉死，
        // 下载这边没有）
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        var attempts = 0
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                attempts++
                if (attempts == 1) {
                    File(path).writeText("apk")
                } else {
                    throw RuntimeException("network down")
                }
            }

        viewModel.downloadAndUpdate(file, fakeDownload)
        assertTrue(viewModel.updateDownloaded)

        viewModel.downloadAndUpdate(file, fakeDownload)
        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, proxyUrl, directUrl), calls)
        assertFalse(file.exists())
    }

    @Test
    fun `proxy truncated but direct complete succeeds`() = runBlocking {
        // 发行版写明 100 字节：代理只下到 3 字节（截断）、直链下到
        // 100 字节（完整），应记下载成功。大小校验在 downloadOnce
        // 里每路各算一次，这档之前没有任何单测——旧用例里两路要么
        // 都完整、要么都截断；校验若只算一次，代理那次 3 字节就会
        // 把整次下载判死，直链完整也救不回来
        val viewModel = setupViewModel(proxyUrl, downloadSize = 100)
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                if (url == proxyUrl) File(path).writeText("apk")
                else File(path).writeText("x".repeat(100))
            }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)
        assertEquals(100L, file.length())
    }

    @Test
    fun `download progress callback shows percentage toast`() = runBlocking {
        // 假下载调一次进度回调 50 再卡住：downloadOnce 传给下载函数
        // 的进度回调应弹“正在下载更新: 50%”；没传回调时用户下大包
        // 全程看不到进度，旧单测发现不了（旧假下载一次也没调过
        // 这个回调）。“开始下载更新”与进度提示是两条提示，换场要
        // 300 毫秒，多等一倍（600 毫秒）再断言，免得机器慢时误判
        val viewModel = setupViewModel(proxyUrl)
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { _, path, onProgress ->
                onProgress?.invoke(50)
                started.complete(Unit)
                releaseGate.await()
                File(path).writeText("apk")
            }

        val first = async { viewModel.downloadAndUpdate(file, fakeDownload) }
        started.await()
        delay(600)
        assertTrue(LeanbackToastState.I.visible)
        assertEquals("正在下载更新: 50%", LeanbackToastState.I.current.message)

        releaseGate.complete(Unit)
        first.await()
        assertTrue(viewModel.updateDownloaded)
        assertTrue(file.exists())
    }

    @Test
    fun `failed download resets updating flag so retry works`() = runBlocking {
        // 第一次下：代理与直链都失败；第二次下：代理一次成功。
        // _isUpdating 在 finally 里复位没有任何单测——旧用例全是
        // 同一台 ViewModel 只下一次；没 finally 时第一次失败后
        // 标志还留着 true，第二次调用会被重入守卫直接拦掉、一次
        // 也不下载，更新页从此再也下不了包，旧单测发现不了
        val viewModel = setupViewModel(proxyUrl)
        val calls = mutableListOf<String>()
        val file = tempFile()
        var attempts = 0
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, path, _ ->
                calls.add(url)
                attempts++
                if (attempts <= 2) throw RuntimeException("network down")
                File(path).writeText("apk")
            }

        viewModel.downloadAndUpdate(file, fakeDownload)
        assertFalse(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl), calls)

        viewModel.downloadAndUpdate(file, fakeDownload)
        assertTrue(viewModel.updateDownloaded)
        assertEquals(listOf(proxyUrl, directUrl, proxyUrl), calls)
        assertTrue(file.exists())
    }

    @Test
    fun `size matches passes the download`() = runBlocking {
        // 发行版写明 3 字节、实际也下到 3 字节：大小对得上就放行，
        // 不能当成下载失败。这档之前没有任何单测——旧用例只测过
        // 大小不对（100 vs 3 字节）与不校验（downloadSize = -1）；
        // 校验写反（相等也抛错）时这里会失败，旧单测发现不了
        val viewModel = setupViewModel(proxyUrl, downloadSize = 3)
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
        assertTrue(file.exists())
    }

    @Test
    fun `no update available skips download entirely`() = runBlocking {
        // 没查过更新（isUpdateAvailable 为 false）：第一行守卫直接返回，
        // 一次也不下载、也不删文件。这档之前没有任何单测——旧用例
        // 全是 setupViewModel 先查出有更新再下；没这行守卫时这里会
        // 直接下 _latestRelease 里的默认空地址，旧单测发现不了
        val viewModel = LeanBackUpdateViewModel()
        val calls = mutableListOf<String>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { url, _, _ -> calls.add(url) }

        viewModel.downloadAndUpdate(file, fakeDownload)

        assertFalse(viewModel.updateDownloaded)
        assertTrue(calls.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `failed download shows failure toast`() = runBlocking {
        // 两路都失败：外层 catch 弹“下载更新失败”一次也没断言过——
        // 没这句提示时用户只看到下载没反应；提示换场要 300 毫秒，
        // 多等一倍（600 毫秒）再断言，和进度提示那档同一写法
        val viewModel = setupViewModel(proxyUrl)
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { _, _, _ -> throw RuntimeException("network down") }

        viewModel.downloadAndUpdate(file, fakeDownload)
        delay(600)

        assertFalse(viewModel.updateDownloaded)
        assertFalse(file.exists())
        assertTrue(LeanbackToastState.I.visible)
        assertEquals("下载更新失败", LeanbackToastState.I.current.message)
    }

    @Test
    fun `download start shows start toast`() = runBlocking {
        // 下载开始就弹“开始下载更新”：下大包、进度回调还没到之前，
        // 用户全靠这一句知道下载动了；和失败提示同一类，一次也
        // 没断言过——卡在假下载里、进度回调还没调时断言
        val viewModel = setupViewModel(proxyUrl)
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { _, path, _ ->
                started.complete(Unit)
                releaseGate.await()
                File(path).writeText("apk")
            }

        val first = async { viewModel.downloadAndUpdate(file, fakeDownload) }
        started.await()
        delay(600)
        assertTrue(LeanbackToastState.I.visible)
        assertEquals("开始下载更新", LeanbackToastState.I.current.message)

        releaseGate.complete(Unit)
        first.await()
        assertTrue(viewModel.updateDownloaded)
        assertTrue(file.exists())
    }

    @Test
    fun `successful download shows success toast`() = runBlocking {
        // 下完弹“下载更新成功”：和失败提示同一类，一次也没断言过；
        // 提示换场要 300 毫秒，多等一倍再断言
        val viewModel = setupViewModel(proxyUrl)
        val file = tempFile()
        val fakeDownload: suspend (String, String, ((Int) -> Unit)?) -> Unit =
            { _, path, _ -> File(path).writeText("apk") }

        viewModel.downloadAndUpdate(file, fakeDownload)
        delay(600)

        assertTrue(viewModel.updateDownloaded)
        assertTrue(LeanbackToastState.I.visible)
        assertEquals("下载更新成功", LeanbackToastState.I.current.message)
    }
}
