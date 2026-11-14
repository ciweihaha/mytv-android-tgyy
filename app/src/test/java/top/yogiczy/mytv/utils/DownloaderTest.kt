package top.yogiczy.mytv.utils

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.HttpStatusException
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloaderTest {
    private fun tempFile() =
        File.createTempFile("download-test-", ".apk").apply { deleteOnExit() }

    /**
     * 取消下载要立刻停下来：只发响应头、不发正文，让下载卡在读流那步；
     * 取消后应在几秒内抛取消异常——没修法时读流要等 30 秒读超时才抛
     * IOException、再被包成网络错，withTimeout(10 秒) 先超时失败。
     */
    @Test
    fun `cancelling mid download throws CancellationException promptly`() = runBlocking {
        // 只发响应头、不发正文：下载卡在读流上
        val headersSent = CountDownLatch(1)
        val holdBody = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/slow.apk") { exchange ->
                exchange.sendResponseHeaders(200, 100L * 1024 * 1024)
                headersSent.countDown()
                // 10 秒内一个字节都不发，下载一直卡在读流上
                holdBody.await(10, TimeUnit.SECONDS)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/slow.apk"
            val file = tempFile()
            val deferred = async(Dispatchers.Default) {
                Downloader.downloadTo(url, file.path, null)
            }
            // 等响应头到了、下载确实卡在读流上再取消
            assertTrue(headersSent.await(10, TimeUnit.SECONDS))
            delay(500)

            val start = System.currentTimeMillis()
            deferred.cancel()
            val thrown = try {
                withTimeout(10_000) { deferred.await() }
                null
            } catch (e: Throwable) {
                e
            }
            val elapsed = System.currentTimeMillis() - start

            // 没修法时 withTimeout 先到、抛的是超时取消，不是下载本身的取消
            assertTrue(
                "取消后应立刻抛取消异常，实际是 $thrown（${elapsed}ms）",
                thrown is CancellationException &&
                    thrown !is TimeoutCancellationException,
            )
            assertTrue("取消后应在 5 秒内停下，实际 ${elapsed}ms", elapsed < 5_000)
        } finally {
            holdBody.countDown()
            server.stop(0)
        }
    }

    /** 服务器回明确状态码时原样抛 HttpStatusException，不包成网络错。 */
    @Test
    fun `http error status throws HttpStatusException with the status code`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/missing.apk") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/missing.apk"
            val file = tempFile()
            val thrown = try {
                withTimeout(10_000) {
                    Downloader.downloadTo(url, file.path, null)
                }
                null
            } catch (e: Throwable) {
                e
            }
            assertTrue(
                "404 应原样抛 HttpStatusException，实际是 $thrown",
                thrown is HttpStatusException && thrown.code == 404,
            )
        } finally {
            server.stop(0)
        }
    }

    /** 正常路不受影响：小文件一次下完、内容一致。 */
    @Test
    fun `downloads small file completely`() = runBlocking {
        val body = "apk-bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/small.apk") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/small.apk"
            val file = tempFile()
            withTimeout(10_000) {
                Downloader.downloadTo(url, file.path, null)
            }
            assertEquals("apk-bytes", file.readText())
        } finally {
            server.stop(0)
        }
    }
}
