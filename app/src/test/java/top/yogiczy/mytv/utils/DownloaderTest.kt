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

    /** 服务器回 500 时原样抛 HttpStatusException，不包成网络错、不写文件。 */
    @Test
    fun `http 500 throws HttpStatusException with the status code and leaves target untouched`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/error.apk") { exchange ->
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
                start()
            }
            try {
                val url = "http://127.0.0.1:${server.address.port}/error.apk"
                val file = tempFile().apply { writeText("old") }
                val thrown = try {
                    withTimeout(10_000) {
                        Downloader.downloadTo(url, file.path, null)
                    }
                    null
                } catch (e: Throwable) {
                    e
                }
                assertTrue(
                    "500 应原样抛 HttpStatusException，实际是 $thrown",
                    thrown is HttpStatusException && thrown.code == 500,
                )
                assertTrue(
                    "500 的文案应报服务器返回 500、不是网络错，实际是 ${thrown?.message}",
                    thrown?.message?.contains("服务器返回 500") == true &&
                        thrown?.message?.contains("请检查网络连接") == false,
                )
                assertEquals("old", file.readText())
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

    /** 404 不写文件：状态码检查在开文件流之前，旧文件原样留着。 */
    @Test
    fun `http error status leaves target file untouched`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/missing.apk") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/missing.apk"
            val file = tempFile().apply { writeText("old") }
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
            assertEquals("old", file.readText())
        } finally {
            server.stop(0)
        }
    }

    /** 连不上服务器包成网络错：不是 HTTP 状态码，走“请检查网络连接”那条路。 */
    @Test
    fun `connection refused throws network message and leaves target untouched`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
        val port = server.address.port
        server.stop(0)
        val file = tempFile().apply { writeText("old") }
        val thrown = try {
            withTimeout(10_000) {
                Downloader.downloadTo("http://127.0.0.1:$port/x", file.path, null)
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            "连不上应包成网络错，实际是 $thrown",
            thrown is Exception && thrown.message?.contains("请检查网络连接") == true,
        )
        assertEquals("old", file.readText())
    }

    /** 已知总长度时进度回调按百分比变化触发、重复值只报一次、最后到 100。 */
    @Test
    fun `progress callback fires increasing percentages ending at 100`() = runBlocking {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/big.apk") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/big.apk"
            val file = tempFile()
            val progressValues = mutableListOf<Int>()
            withTimeout(10_000) {
                Downloader.downloadTo(url, file.path) { progressValues.add(it) }
            }
            assertEquals(body.size.toLong(), file.length())
            assertTrue("进度回调应至少报两次，实际 $progressValues", progressValues.size >= 2)
            assertEquals(100, progressValues.last())
            assertTrue(
                "进度回调应严格递增（重复值只报一次），实际 $progressValues",
                progressValues.zipWithNext().all { (a, b) -> a < b },
            )
            assertTrue(progressValues.all { it in 0..100 })
        } finally {
            server.stop(0)
        }
    }

    /** 分块传输（总长度未知）时不报进度：contentLength 为 -1，回调一次也不触发。 */
    @Test
    fun `chunked response never fires progress callback`() = runBlocking {
        val body = "chunked-bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chunked.apk") { exchange ->
                // 长度 0 = 分块传输，总长度未知
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/chunked.apk"
            val file = tempFile()
            val progressValues = mutableListOf<Int>()
            withTimeout(10_000) {
                Downloader.downloadTo(url, file.path) { progressValues.add(it) }
            }
            assertEquals("chunked-bytes", file.readText())
            assertTrue(
                "总长度未知时进度回调一次也不应触发，实际 $progressValues",
                progressValues.isEmpty(),
            )
        } finally {
            server.stop(0)
        }
    }

    /** 目标父目录不存在：建文件流失败包中文原因、不留残文件；downloadTo 本身不建目录。 */
    @Test
    fun `missing parent dir throws write failure message and leaves no file`() = runBlocking {
        // 壳 823 轮姊妹检查：建输出流失败单独包一层“无法写入目标文件”，
        // 别再包成“请检查网络连接”误导用户去查网络；1946 轮断言的
        // 是旧的网络错文案，这轮随生产修法改过来
        val body = "apk-bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ok.apk") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/ok.apk"
            val target = File(
                File(tempFile().parentFile, "no-such-dir-${System.nanoTime()}"),
                "latest.apk",
            )
            val thrown = try {
                withTimeout(10_000) {
                    Downloader.downloadTo(url, target.path, null)
                }
                null
            } catch (e: Throwable) {
                e
            }
            assertTrue(
                "父目录不存在应报无法写入目标文件，实际是 $thrown",
                thrown is Exception && thrown.message?.contains("无法写入目标文件") == true,
            )
            assertTrue("目标文件不应存在", !target.exists())
        } finally {
            server.stop(0)
        }
    }
}
