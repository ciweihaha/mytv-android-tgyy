package top.yogiczy.mytv.data.repositories.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.data.utils.HttpStatusException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 发行版拉取错误映射测试：latestRelease 只有网络调用 + 四档错误映射，
 * 解析本身是 GitReleaseParserTest 的事；这里用本地服务器走通整条路。
 * 地址里带 "github.com" 是为了让 GitHub 解析器接手——isSupport 只看
 * 子串，主机仍是 127.0.0.1，不走外网。
 * 天光拉的是 releases/latest 单对象接口（不是列表接口），所以空数据
 * 那档是顶层数组 `[]`：解析器报类型错，仓库层要原样透传、不能包成
 * “请检查网络连接”。
 * 取消那档也不在这里测：UpdateViewModelTest 已覆盖“检查中途取消不记失败”。
 */
class GitRepositoryTest {
    /**
     * 最小 HTTP 服务（壳 949 轮同款写法：com.sun.net.httpserver
     * 不在 Android 单测的编译 classpath 里）。
     * /github.com/releases 回合法发行版 JSON（单对象），
     * /github.com/empty 回空数组（解析器报类型错），
     * 其余一律 404。
     */
    private class TestHttpServer {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        private val thread = Thread({
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        var line: String?
                        do {
                            line = reader.readLine()
                        } while (line != null && line.isNotEmpty())
                        val out = socket.getOutputStream()
                        when {
                            requestLine.startsWith("GET /github.com/releases ") -> {
                                val content = """{"tag_name": "V2.2.0.219", "body": "notes", "assets": [{"name": "mytv-android-tv-2.2.0.219-all-sdk23-original.apk", "browser_download_url": "https://example.com/original.apk"}, {"name": "mytv-android-tv-2.2.0.219-arm64-v8a-sdk23-original.apk", "browser_download_url": "https://example.com/arm64.apk"}]}""".toByteArray()
                                out.write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                out.write(content)
                                out.flush()
                            }

                            requestLine.startsWith("GET /github.com/empty ") -> {
                                val content = "[]".toByteArray()
                                out.write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                out.write(content)
                                out.flush()
                            }

                            else -> {
                                out.write(
                                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .toByteArray()
                                )
                                out.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 服务关闭
            }
        }, "test-http-server").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop() = runCatching { serverSocket.close() }
    }

    @Test
    fun `unsupported update source throws before any network call`() {
        // 不支持的更新源必须在联网前就抛 IllegalArgumentException；
        // 放进网络 try 里会被包成“请检查网络连接”，配置错了也误导
        // 用户去查网络（第 73 轮只改了内层异常，外层照包不误）
        var thrown: Throwable? = null
        runBlocking {
            try {
                GitRepository().latestRelease("https://example.com/releases/latest")
            } catch (e: Exception) {
                thrown = e
            }
        }
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown?.message?.contains("不支持的更新源") == true)
    }

    @Test
    fun latestRelease_success_returnsParsedRelease() = runBlocking {
        val server = TestHttpServer().apply { start() }
        try {
            val release = GitRepository().latestRelease(
                "http://127.0.0.1:${server.port}/github.com/releases"
            )
            assertEquals("2.2.0.219", release.version)
            assertEquals(
                "${Constants.GITHUB_PROXY}https://example.com/arm64.apk",
                release.downloadUrl,
            )
            assertEquals("notes", release.description)
        } finally {
            server.stop()
        }
    }

    @Test
    fun latestRelease_notFound_throwsHttpStatusException() {
        val server = TestHttpServer().apply { start() }
        try {
            val ex = assertThrows(HttpStatusException::class.java) {
                runBlocking {
                    GitRepository().latestRelease(
                        "http://127.0.0.1:${server.port}/github.com/missing"
                    )
                }
            }
            assertEquals(404, ex.code)
        } finally {
            server.stop()
        }
    }

    @Test
    fun latestRelease_topLevelArray_throwsParseErrorNotNetworkError() = runBlocking {
        // 顶层是数组不是对象：解析在网络 try 外面，类型错要原样透传
        // 给界面，不能包成“请检查网络连接”
        val server = TestHttpServer().apply { start() }
        try {
            val result = runCatching {
                GitRepository().latestRelease(
                    "http://127.0.0.1:${server.port}/github.com/empty"
                )
            }
            val ex = result.exceptionOrNull()
            assertTrue(ex is IllegalArgumentException)
            assertTrue(ex?.message?.contains("JsonObject") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun latestRelease_connectionRefused_throwsNetworkMessage() = runBlocking {
        // 连不上是网络问题：包成中文提示；127.0.0.1:9 拒绝连接是
        // 立刻失败、没有重试，不会拖慢测试
        val result = runCatching {
            GitRepository().latestRelease("http://127.0.0.1:9/github.com/x")
        }
        assertEquals(
            "获取最新发行版失败，请检查网络连接",
            result.exceptionOrNull()?.message,
        )
    }
}
