package top.yogiczy.mytv.data.repositories.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.data.utils.HttpStatusException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

class IptvRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    @Test
    fun `getIptvGroupList simplify end to end keeps cctv and satellite only`() = runBlocking {
        // getIptvGroupList 本身一次也没端到端断言过：simplifyTest 与
        // checkIptvGroupListNotEmpty 只在单元层测过，方法里的精简映射
        // （按台名过滤）与空分组丢弃（过滤后没台的分组整组去掉）都没
        // 走通过——映射里丢了 filter 空分组那行、没台的分组会漏进界面。
        // 缓存预先写好、有效期给足，地址故意写 127.0.0.1:9：缓存命中
        // 时一下也不联网，走了刷新去下载立刻连接被拒、用例失败
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
            #EXTINF:-1 tvg-name="BBC 新闻" group-title="国际",BBC 新闻
            http://example.com/bbc.m3u8
            #EXTINF:-1 tvg-name="凤凰中文" group-title="港澳",凤凰中文
            http://example.com/phoenix.m3u8
        """.trimIndent()
        File(tempFolder.root, "iptv.txt").writeText(m3u)

        val result = IptvRepository().getIptvGroupList(
            "http://127.0.0.1:9/live.m3u",
            cacheTime = 60_000,
            simplify = true,
        )

        // 只剩央视与卫视两个分组；国际、港澳两个分组过滤后没台、
        // 整组去掉，不留空分组
        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(listOf("CCTV-1"), result[0].iptvList.map { it.name })
        assertEquals("卫视", result[1].name)
        assertEquals(listOf("湖南卫视"), result[1].iptvList.map { it.name })
    }

    @Test
    fun `getIptvGroupList without simplify keeps all groups`() = runBlocking {
        // 不精简那条路一次也没端到端断言过：else 分支原样返回解析结果，
        // 一旦被改成也过滤，四个分组会被悄悄砍成两个、用例失败。缓存
        // 与地址写法与上面那条同款，全程不联网
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
            #EXTINF:-1 tvg-name="BBC 新闻" group-title="国际",BBC 新闻
            http://example.com/bbc.m3u8
            #EXTINF:-1 tvg-name="凤凰中文" group-title="港澳",凤凰中文
            http://example.com/phoenix.m3u8
        """.trimIndent()
        File(tempFolder.root, "iptv.txt").writeText(m3u)

        val result = IptvRepository().getIptvGroupList(
            "http://127.0.0.1:9/live.m3u",
            cacheTime = 60_000,
            simplify = false,
        )

        assertEquals(4, result.size)
        assertEquals(listOf("央视", "卫视", "国际", "港澳"), result.map { it.name })
        assertEquals(listOf("CCTV-1"), result[0].iptvList.map { it.name })
        assertEquals(listOf("湖南卫视"), result[1].iptvList.map { it.name })
        assertEquals(listOf("BBC 新闻"), result[2].iptvList.map { it.name })
        assertEquals(listOf("凤凰中文"), result[3].iptvList.map { it.name })
    }

    @Test
    fun `getIptvGroupList remote unreachable throws chinese error`() = runBlocking {
        // 远程源没缓存、连不上：拉取失败要包成中文错抛出来，不能把连接
        // 被拒的 IOException 原样抛到主界面；127.0.0.1:9 拒绝连接是
        // 立刻失败、没有重试，不会拖慢测试
        // 文案是用户看得到的错误页消息：原来只断 contains("获取远程直播源
        // 失败")，“，请检查网络连接”这半句丢了也不会红；节目单（852 轮
        // 姊妹检查）与发行版两处都已钉死全句，这处补齐三处一致
        val result = runCatching {
            IptvRepository().getIptvGroupList("http://127.0.0.1:9/live.m3u", cacheTime = 60_000)
        }
        assertEquals(
            "获取远程直播源失败，请检查网络连接",
            result.exceptionOrNull()?.message,
        )
    }

    /**
     * 本地假服务器：只回状态码（GitRepositoryTest 同款写法，JDK 自带
     * ServerSocket，Android 单测的编译 classpath 里没有 MockWebServer）。
     * /servererror.m3u 回 500，其余一律 404。
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
                        val statusLine = if (requestLine.startsWith("GET /servererror.m3u ")) {
                            "HTTP/1.1 500 Internal Server Error"
                        } else {
                            "HTTP/1.1 404 Not Found"
                        }
                        out.write(
                            "$statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        out.flush()
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
    fun `getIptvGroupList server 404 throws HttpStatusException as-is`() = runBlocking {
        // 服务器回了明确状态码就原样抛：不是网络问题，别包成"请检查网络
        // 连接"误导用户去查网络。连不上那档已有用例（包成中文错），
        // 404 原样抛那档一次也没断言过——fetchSource 的 catch 里那行
        // `if (ex is HttpStatusException) throw ex` 删了也不会红
        val server = TestHttpServer().apply { start() }
        try {
            val result = runCatching {
                IptvRepository().getIptvGroupList(
                    "http://127.0.0.1:${server.port}/notfound.m3u",
                    cacheTime = 60_000,
                )
            }
            val ex = result.exceptionOrNull()
            assertTrue(ex is HttpStatusException)
            assertEquals(404, (ex as HttpStatusException).code)
            assertTrue(ex.message?.contains("服务器返回 404") == true)
            assertFalse(ex.message?.contains("请检查网络连接") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `getIptvGroupList server 500 throws HttpStatusException as-is`() = runBlocking {
        // 与 404 同款：500 也要原样抛，码值不同就是两档（2249 轮同款教训：
        // 错误映射要按码值数齐，404 钉了不等于 500 钉了）
        val server = TestHttpServer().apply { start() }
        try {
            val result = runCatching {
                IptvRepository().getIptvGroupList(
                    "http://127.0.0.1:${server.port}/servererror.m3u",
                    cacheTime = 60_000,
                )
            }
            val ex = result.exceptionOrNull()
            assertTrue(ex is HttpStatusException)
            assertEquals(500, (ex as HttpStatusException).code)
            assertTrue(ex.message?.contains("服务器返回 500") == true)
            assertFalse(ex.message?.contains("请检查网络连接") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `simplify keeps cctv regardless of case`() {
        assertTrue(simplifyTest(Iptv(name = "CCTV-1")))
        assertTrue(simplifyTest(Iptv(name = "cctv-1")))
        assertTrue(simplifyTest(Iptv(name = "CCTV-5+")))
        assertTrue(simplifyTest(Iptv(name = "CCTV")))
    }

    @Test
    fun `simplify keeps channels ending with satellite suffix`() {
        assertTrue(simplifyTest(Iptv(name = "湖南卫视")))
        assertTrue(simplifyTest(Iptv(name = "北京卫视")))
        assertTrue(simplifyTest(Iptv(name = "凤凰卫视")))
        assertTrue(simplifyTest(Iptv(name = "卫视")))
    }

    @Test
    fun `simplify drops others`() {
        assertFalse(simplifyTest(Iptv(name = "凤凰中文")))
        assertFalse(simplifyTest(Iptv(name = "BBC 新闻")))
        assertFalse(simplifyTest(Iptv(name = "")))
        // cctv 不在开头、卫视不在结尾时都不要：这档区分“开头/结尾”
        // 与“包含”，改成 contains 会让这两档翻过来
        assertFalse(simplifyTest(Iptv(name = "高清CCTV-1")))
        assertFalse(simplifyTest(Iptv(name = "湖南卫视高清")))
    }
    @Test
    fun `empty group list throws`() {
        assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = false)
        }
    }

    @Test
    fun `empty group list after simplify throws with simplify hint`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = true)
        }
        assertTrue(ex.message?.contains("精简") == true)
    }

    @Test
    fun `empty group list throws EmptyIptvSourceException`() {
        // 空源报错的文案直接显示在错误页上（MainViewModel 把 ex.message
        // 放进 LeanbackMainUiState.Error），只断言类型不够：文案被改掉也没人发现
        val ex = assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = false)
        }
        assertTrue(ex.message?.contains("直播源里没有频道") == true)
    }

    @Test
    fun `empty group list after simplify throws EmptyIptvSourceException`() {
        val ex = assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = true)
        }
        assertTrue(ex.message?.contains("精简后没有频道") == true)
    }

    @Test
    fun `groups with no channels throw`() {
        assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(
                IptvGroupList(listOf(IptvGroup("央视", IptvList(emptyList())))),
                simplified = false,
            )
        }
    }

    @Test
    fun `non-empty group list passes`() {
        // 不抛错即通过
        checkIptvGroupListNotEmpty(
            IptvGroupList(
                listOf(
                    IptvGroup(
                        "央视",
                        IptvList(listOf(Iptv(name = "CCTV-1"))),
                    )
                )
            ),
            simplified = false,
        )
    }
}
