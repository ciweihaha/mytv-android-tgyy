package top.yogiczy.mytv.data.repositories.epg

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.data.utils.HttpStatusException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class EpgRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    /** 按解析器主格式（yyyyMMddHHmmss Z，默认时区）格式化时间戳，供 XML 单测拼正在播的节目 */
    private fun formatEpgTime(millis: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).format(Date(millis))

    /**
     * 最小 HTTP 服务（2223 轮 GitRepositoryTest 同款写法：com.sun.net.httpserver
     * 不在 Android 单测的编译 classpath 里）。任何 GET 都回同一份节目单 XML，
     * 供“主源拉取失败走备用源”那档用。
     */
    private class TestHttpServer(private val body: String) {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        private val thread = Thread({
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        var line: String?
                        do {
                            line = reader.readLine()
                        } while (line != null && line.isNotEmpty())
                        val content = body.toByteArray()
                        val out = socket.getOutputStream()
                        out.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray()
                        )
                        out.write(content)
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

    /**
     * 只回状态码的本地假服务器（GitRepositoryTest 同款写法）：主源与备用源
     * 都指到它时，fetchXml 里 `if (ex is HttpStatusException) throw ex`
     * 那行才走得到——只测“连不上包中文错”那档，删了这行也不会红。
     * /servererror.xml 回 500，其余一律 404。
     */
    private class TestStatusHttpServer {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        /** 接到的请求总数：主源 1 次＋备用源 1 次才是“状态错不重试” */
        val hits = AtomicInteger(0)

        private val thread = Thread({
            try {
                while (!serverSocket.isClosed) {
                    serverSocket.accept().use { socket ->
                        hits.incrementAndGet()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        var line: String?
                        do {
                            line = reader.readLine()
                        } while (line != null && line.isNotEmpty())
                        val out = socket.getOutputStream()
                        val statusLine = if (requestLine.startsWith("GET /servererror.xml ")) {
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
        }, "test-status-http-server").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop() = runCatching { serverSocket.close() }
    }

    @Test
    fun `clearCache also clears the raw xml cache`() {
        // 节目单是两级缓存（epg.xml 原文 + epg.json 解析结果）：只清
        // 解析结果时，换源后新源拉取失败会回落到旧源的 epg.xml，把旧
        // 源的节目单当成新源的显示
        File(tempFolder.root, "epg.json").writeText("{}")
        File(tempFolder.root, "epg.xml").writeText("<tv></tv>")

        EpgRepository().clearCache()

        assertFalse(File(tempFolder.root, "epg.json").exists())
        assertFalse(File(tempFolder.root, "epg.xml").exists())
    }

    @Test
    fun `clearCache leaves other files alone`() {
        File(tempFolder.root, "epg.json").writeText("{}")
        File(tempFolder.root, "iptv.txt").writeText("channels")

        EpgRepository().clearCache()

        assertFalse(File(tempFolder.root, "epg.json").exists())
        assertTrue(File(tempFolder.root, "iptv.txt").exists())
    }

    @Test
    fun `keepEpgChannel keeps channel when display-name matches`() {
        val filter = setOf("CCTV-1 综合", "cctv1")

        assertTrue(keepEpgChannel("cctv1", "CCTV-1 综合", filter))
    }

    @Test
    fun `keepEpgChannel keeps channel when only id matches`() {
        // tvg-name 是纯 id 的源：过滤集里只有 "cctv1"，display-name
        // "CCTV-1 综合" 对不上；id 对上也留，否则解析时整台被滤掉
        val filter = setOf("cctv1")

        assertTrue(keepEpgChannel("cctv1", "CCTV-1 综合", filter))
    }

    @Test
    fun `keepEpgChannel matches id case-insensitively`() {
        // 备用源的频道 id 是大写（CCTV1）、直播源的 tvg-id 是小写
        // （cctv1）：大小写敏感比会对不上、整台被滤掉
        val filter = setOf("cctv1")

        assertTrue(keepEpgChannel("CCTV1", "CCTV1", filter))
    }

    @Test
    fun `keepEpgChannel matches display-name case-insensitively`() {
        val filter = setOf("CCTV-1 综合")

        assertTrue(keepEpgChannel("cctv1", "cctv-1 综合", filter))
    }

    @Test
    fun `keepEpgChannel drops channel when neither matches`() {
        val filter = setOf("cctv1")

        assertFalse(keepEpgChannel("cctv2", "CCTV-2 财经", filter))
    }

    @Test
    fun `keepEpgChannel keeps all when filter is empty`() {
        assertTrue(keepEpgChannel("cctv1", "CCTV-1 综合", emptySet()))
    }

    @Test
    fun `keepEpgChannel drops channel without id or display-name`() {
        val filter = setOf("cctv1")

        assertFalse(keepEpgChannel(null, "CCTV-1 综合", filter))
        assertFalse(keepEpgChannel("cctv1", null, filter))
    }

    @Test
    fun `usable programme has real start and end after start`() {
        assertTrue(isUsableEpgProgramme(EpgProgramme(startAt = 1000, endAt = 2000, title = "新闻")))
    }

    @Test
    fun `programme with unparsable times is not usable`() {
        // 开始、结束解析失败都记 0：弹窗里会显示一条 08:00 ~ 08:00
        // 的假节目，经典面板还会多出一个 1970 年的日期栏
        assertFalse(isUsableEpgProgramme(EpgProgramme(startAt = 0, endAt = 0, title = "新闻")))
        assertFalse(isUsableEpgProgramme(EpgProgramme(startAt = 0, endAt = 2000, title = "新闻")))
        // 结束是 0、开始在将来时，还可能被当成“稍后播放”挑出来
        assertFalse(isUsableEpgProgramme(EpgProgramme(startAt = 2000, endAt = 0, title = "新闻")))
    }

    @Test
    fun `programme with end not after start is not usable`() {
        assertFalse(isUsableEpgProgramme(EpgProgramme(startAt = 2000, endAt = 2000, title = "新闻")))
        assertFalse(isUsableEpgProgramme(EpgProgramme(startAt = 2000, endAt = 1000, title = "新闻")))
    }

    @Test
    fun `no cache before threshold means refresh not empty epg`() {
        // 未到刷新时间点、又没缓存（首次启动、清过缓存）时返回 null，
        // 调用方不等时间点直接走刷新；原先直接给空节目单，0 点到
        // 阈值（默认 2 点）之间一直显示“无节目”
        assertNull(cachedEpgBeforeThreshold(null))
        assertNull(cachedEpgBeforeThreshold(""))
        assertNull(cachedEpgBeforeThreshold("   "))
    }

    @Test
    fun `corrupt cache before threshold means refresh not empty epg`() {
        assertNull(cachedEpgBeforeThreshold("not json"))
        assertNull(cachedEpgBeforeThreshold("""[{"channel":"CCTV-1""""))
    }

    @Test
    fun `usable cache before threshold is returned as-is`() {
        val json = Json.encodeToString(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = 1000, endAt = 2000, title = "新闻"))
                    )
                )
            )
        )

        val cached = cachedEpgBeforeThreshold(json)

        assertEquals(1, cached?.size)
        assertEquals("CCTV-1", cached?.first()?.channel)
        assertEquals("新闻", cached?.first()?.programmes?.first()?.title)
    }

    @Test
    fun `prefetch returns null when cache is usable before threshold`() = runBlocking {
        // 未到刷新时间点且 JSON 缓存可用：调用方直接用缓存，不下载。
        // 阈值传 24（小时只到 23），任何时刻都算“未到时间点”，不依赖
        // 现在几点；只断言不下载，不碰网络
        File(tempFolder.root, "epg.json").writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = 1000, endAt = 2000, title = "新闻"))
                        )
                    )
                )
            )
        )

        val result = EpgRepository().downloadEpgXmlIfNeeded("https://example.com/epg.xml", 24)

        assertNull(result)
    }

    @Test
    fun `prefetch returns null when json cache is from today`() = runBlocking {
        // JSON 缓存是今天的、且里面有正在播的节目：调用方直接用缓存，
        // 不下载。阈值传 0 跳过“未到时间点”那条分支，只走“缓存是今天”
        // 这一条；只断言不下载，不碰网络。时间戳必须正在播——只有今天
        // 的日期、内容却是昨天的旧缓存，现在也要下载（见下面那条单测
        // 锁的 getEpgList 端到端行为）
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(
                                EpgProgramme(
                                    startAt = now - 30 * 60 * 1000L,
                                    endAt = now + 30 * 60 * 1000L,
                                    title = "新闻",
                                )
                            )
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now)

        val result = EpgRepository().downloadEpgXmlIfNeeded("https://example.com/epg.xml", 0)

        assertNull(result)
    }

    @Test
    fun `getEpgList uses prefetched xml without downloading`() = runBlocking {
        // P1 预下载的核心行为：启动时并行拉好的 XML 直接用，getEpgList
        // 不再等直播源就绪后重新下载。之前只有 downloadEpgXmlIfNeeded
        // 两条“不用下载”分支有单测，getEpgList 收到预下载 XML 后到底
        // 用不用它、还是照样去下载，一直没人锁死。
        // 地址故意写成 127.0.0.1:9（discard 端口、无人监听）：一旦实现
        // 回退到重新下载，立刻连接被拒抛错、用例失败；走预下载时地址
        // 碰都不碰，单测全程不联网
        // 备用源地址也一样写 discard 端口：本文件大部分用例的预下载
        // XML 都是写死的过期日期（20261113），不断言兜底时备用源一
        // 拉就命中、结果会被备用源盖掉；只测兜底本身的那条用例才传
        // 真实可用的备用源（预先写好的 epg.xml 缓存）
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList parses negative timezone on the main path`() = runBlocking {
        // 主解析只认 `yyyyMMddHHmmss Z`（时区前有空格）：美洲源的
        // 负时区 -0800 只能走主解析，兜底测过（1924 轮）、主解析
        // 一次也没断言过。09:00-0800 是 UTC 17:00，即 +0800 的
        // 次日 01:00；只看标题断言不到时间，必须断言 startAt/endAt
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113090000 -0800" stop="20261113100000 -0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1794589200000L, result.first().programmes.first().startAt)
        assertEquals(1794592800000L, result.first().programmes.first().endAt)
    }

    @Test
    fun `getEpgList drops programmes with invalid dates`() = runBlocking {
        // 壳 830 轮姊妹检查：主解析与兜底原来都是宽容模式，非法日期
        // 会顺延成有效日期（2 月 30 日算成 3 月 2 日）进列表、节目
        // 出现在错误的那天；严格模式下解析失败记 0，isUsableEpgProgramme
        // 把整条节目过滤掉，好节目不受影响
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20260230080000 +0800" stop="20260230090000 +0800" channel="cctv1"><title>不存在的日期</title></programme>
              <programme start="20261113080000 +0800" stop="20261301090000 +0800" channel="cctv1"><title>不存在的月份</title></programme>
              <programme start="20261112240000 +0800" stop="20261113010000 +0800" channel="cctv1"><title>不存在的钟点</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList parses double-BOM xml after decodeBody strips it`() = runBlocking {
        // 壳 833 轮姊妹检查：1954 轮只在 decodeBody 那层断言了去 BOM，
        // 去完的串到底能不能进解析器、拼出节目，一直没端到端锁死；
        // 拼起来走一遍：双 BOM 的响应体先过 decodeBody、再当预下载
        // XML 进 getEpgList，节目应正常解析出来。去 BOM 那步一旦
        // 回退，解析器在 "PI must not start with xml" 上抛错、用例失败
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/epg.xml").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(("\uFEFF\uFEFF" + xml).toResponseBody())
            .build()
        val decoded = EpgFetcher.decodeBody(response)

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = decoded,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList refresh uses prefetched xml over stale cache`() = runBlocking {
        // 缓存是昨天的（过期、必须刷新）：刷新走预下载的 XML，而不是
        // 用旧缓存顶上。阈值传 0 跳过“未到时间点”分支；旧缓存故意写
        // 另一台，避免断言误判成“读了缓存也算过”
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = 1000, endAt = 2000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList refreshes when cache is from yesterday even though it has a live programme`() = runBlocking {
        // 壳 851 轮姊妹检查：缓存文件是昨天的、但里面有正在播的节目——
        // 唯一过期理由是日期。上面那条“缓存是昨天的”用例旧缓存里没
        // 正在播的节目，日期那条分支被“没正在播的节目也算过期”那条
        // 盖住了：把日期判断删了它照样通过；只有这条能钉死日期分支。
        // 阈值传 0 跳过“未到时间点”分支；旧缓存故意写另一台，避免
        // 断言误判成“读了缓存也算过”
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(
                                EpgProgramme(
                                    startAt = now - 30 * 60 * 1000L,
                                    endAt = now + 30 * 60 * 1000L,
                                    title = "缓存里的节目",
                                )
                            )
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now - 25 * 60 * 60 * 1000L)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113090000 +0800" stop="20261113100000 +0800" channel="cctv1"><title>新节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList refreshes when cache is from today but has no live programme`() = runBlocking {
        // 712 轮的场景：缓存文件日期是今天、也能解开，但里面全是
        // 已经播完的节目（凌晨拉到的昨天数据带着今天的时间戳存下
        // 来）——只按日期判会沿用一整天、白天全台显示无节目；
        // 没正在播的节目就当过期、必须刷新。阈值传 0 跳过“未到
        // 时间点”分支；旧缓存故意写另一台，避免断言误判
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = now - 2000, endAt = now - 1000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113090000 +0800" stop="20261113100000 +0800" channel="cctv1"><title>新节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新节目", result.first().programmes.first().title)
    }

    @Test
    fun `selectEpgList uses backup when primary has no live programme`() {
        val now = System.currentTimeMillis()
        val primary = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 7200_000, endAt = now - 3600_000, title = "旧节目"))
                    )
                )
            )
        )
        val backup = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 1800_000, endAt = now + 1800_000, title = "备用源正在播"))
                    )
                )
            )
        )

        assertEquals("备用源正在播", selectEpgList(primary, backup, now).first().programmes.first().title)
    }

    @Test
    fun `selectEpgList keeps primary when it has a live programme`() {
        val now = System.currentTimeMillis()
        val primary = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 1800_000, endAt = now + 1800_000, title = "主源正在播"))
                    )
                )
            )
        )
        val backup = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 1800_000, endAt = now + 1800_000, title = "备用源正在播"))
                    )
                )
            )
        )

        assertEquals("主源正在播", selectEpgList(primary, backup, now).first().programmes.first().title)
    }

    @Test
    fun `selectEpgList keeps primary when neither has a live programme`() {
        val now = System.currentTimeMillis()
        val primary = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 7200_000, endAt = now - 3600_000, title = "旧节目"))
                    )
                )
            )
        )
        val backup = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 7200_000, endAt = now - 3600_000, title = "备用源旧节目"))
                    )
                )
            )
        )

        assertEquals("旧节目", selectEpgList(primary, backup, now).first().programmes.first().title)
    }

    @Test
    fun `getEpgList falls back to backup source when primary has no live programme`() = runBlocking {
        // 主源 200 但数据过期（只剩已经播完的节目）：解析后没有正在播
        // 的节目，直接返回主源的话主界面当前节目全空；备用源有正在播
        // 的节目就用备用源。备用源地址故意写成 127.0.0.1:9：拉取被拒
        // 后 getOrRefresh 会把预先写好的 epg.xml 缓存顶上来，全程不
        // 联网；主源走预下载 XML，同样不联网
        val now = System.currentTimeMillis()
        val backupXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 1800_000)}" stop="${formatEpgTime(now + 1800_000)}" channel="cctv1"><title>备用源正在播</title></programme>
            </tv>
        """.trimIndent()
        File(tempFolder.root, "epg.xml").writeText(backupXml)

        val staleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 7200_000)}" stop="${formatEpgTime(now - 3600_000)}" channel="cctv1"><title>昨天节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = staleXml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("备用源正在播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList keeps primary when backup source is unreachable`() = runBlocking {
        // 主源解析出了数据、但没有正在播的节目（过期），备用源又拉不
        // 到（地址连不上、也没有 epg.xml 原文缓存可顶）：这时不该抛
        // 错、也不该给空节目单，沿用主源的旧数据——面板会回落到有节
        // 目的第一天，总比空节目单强。loadEpgList 里第二个 try 的
        // catch 分支（拉备用源失败沿用主源）之前一次也没端到端断言
        // 过：selectEpgList“两边都没有正在播就留主源”只在单元层测
        // 过，备用源拉取失败这条路还没走通过
        val now = System.currentTimeMillis()
        val staleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 7200_000)}" stop="${formatEpgTime(now - 3600_000)}" channel="cctv1"><title>昨天节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = staleXml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("昨天节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList falls back to backup url when primary fetch fails`() = runBlocking {
        // loadEpgList 里“主源拉取失败直接试备用源”那条路之前一次也
        // 没走通过：现有备用源用例全传 prefetchedXml，主源拉取根本
        // 没发生。主源写成 127.0.0.1:9（连不上），备用源走本地服务
        // 回正在播的节目；fromBackup 为真时直接返回备用源解析结果
        val now = System.currentTimeMillis()
        val backupXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 1800_000)}" stop="${formatEpgTime(now + 1800_000)}" channel="cctv1"><title>备用源正在播</title></programme>
            </tv>
        """.trimIndent()
        val server = TestHttpServer(backupXml).apply { start() }
        try {
            val result = EpgRepository().getEpgList(
                "http://127.0.0.1:9/epg.xml",
                refreshTimeThreshold = 0,
                backupXmlUrl = "http://127.0.0.1:${server.port}/backup.xml",
            )

            assertEquals(1, result.size)
            assertEquals("CCTV-1 综合", result.first().channel)
            assertEquals("备用源正在播", result.first().programmes.first().title)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `getEpgList skips malformed programmes without failing the whole list`() = runBlocking {
        // 1184 轮修完后 parseFromXml 终于能在单测里跑了：1158 轮只靠
        // 读代码确认过的缺属性／自闭合兜底，现在真正锁死。五条坏节目
        // 各走一条兜底分支，好节目不受影响、整份节目单不抛错。
        // 地址仍写 127.0.0.1:9，单测全程不联网
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme stop="20261113090000 +0800" channel="cctv1"><title>没开始时间</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><desc>只有简介没标题</desc></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1" />
              <programme start="20261113080000 +0800" stop="20261113090000 +0800"><title>没写频道</title></programme>
              <programme start="20261113090000 +0800" stop="20261113080000 +0800" channel="cctv1"><title>时间倒挂</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList skips programme for undeclared channel`() = runBlocking {
        // 壳 891 轮姊妹检查：节目挂在没声明过的频道 id 上时直接丢掉——
        // 它挂不到任何台上，只会在按名匹配时凭空多出节目。天光这边
        // `channelProgrammes[channelId]?.add` 查不到就跳过，行为在，
        // 但一直没断言过：只测过“没写 channel 属性”（channelId 为 null），
        // 没测过“写了、但这个 id 从没声明过 channel”
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>本台节目</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="unknown"><title>野节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1, result.first().programmes.size)
        assertEquals("本台节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList finds title after desc`() = runBlocking {
        // 壳 891 轮姊妹检查：子元素顺序不是 title 在前时（desc 在前），
        // 按标签名取 title 仍能找到。旧实现取第一个子元素会把简介错
        // 当标题；天光这边内层循环按标签名扫 title，行为在，但一直
        // 没断言过——只测过“只有简介没标题”（跳过整条）、没测过
        // “简介在前、标题在后”
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><desc>简介在前</desc><title>正标题</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1, result.first().programmes.size)
        assertEquals("正标题", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList skips channel without display-name`() = runBlocking {
        // 没 display-name 的频道不进列表、它名下的节目也一起丢掉，
        // 不拖垮整份节目单；有名字的频道照常解析
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <channel id="cctv2" />
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv2"><title>没名字的台的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
    }

    @Test
    fun `getEpgList skips channel whose display-name contains child element`() = runBlocking {
        // display-name 里带子元素时 nextText() 抛错：只跳过这个频道，
        // 不拖垮整份节目单；抛错后解析器停在子元素开头，内层循环继续
        // 走到频道结束标签才停，不会卡死
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <channel id="cctv2"><display-name><b>CCTV-2</b> 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv2"><title>没名字的台的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList skips programme whose title contains child element`() = runBlocking {
        // title 里带子元素时 nextText() 抛错：只跳过这条节目，好节目
        // 不受影响；内层循环继续走到节目结束标签才停
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title><b>带格式的</b>节目名</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList resets programmes when channel is declared twice`() = runBlocking {
        // 同一个 channel id 声明两次：第二次声明把名字与节目列表都重置，
        // 第一次声明名下的节目丢掉——与原先 `epgMap[id] = Epg(...)` 的
        // 覆盖语义一致；源里重复声明频道时不把两份节目拼在一起
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>早节目</title></programme>
              <channel id="cctv1"><display-name>CCTV-1 综合（重声明）</display-name></channel>
              <programme start="20261113090000 +0800" stop="20261113100000 +0800" channel="cctv1"><title>晚节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合（重声明）", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("晚节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList keeps first display-name when channel has two`() = runBlocking {
        // 一个 channel 里写两个 display-name：只取第一个，第二个忽略，
        // 不会把名字拼起来、也不会覆盖
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name><display-name>CCTV-1 别名</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
    }

    @Test
    fun `getEpgList filters channels by display-name`() = runBlocking {
        // 频道过滤端到端：keepEpgChannel 只在单测里直接调过，过滤列表
        // 经 getEpgList 传进 parseFromXml 这条路一直没锁死。按显示名
        // 过滤时只留 CCTV-1，CCTV-2 的频道与节目一起丢掉
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <channel id="cctv2"><display-name>CCTV-2 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv2"><title>另一台的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            filteredChannels = listOf("CCTV-1 综合"),
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
    }

    @Test
    fun `getEpgList filters channels by id`() = runBlocking {
        // 按 tvg-id 过滤同样走得通：名字对不上、id 对上也留下来
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <channel id="cctv2"><display-name>CCTV-2 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv2"><title>另一台的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            filteredChannels = listOf("cctv2"),
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-2 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("另一台的节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList before threshold returns cache without refreshing`() = runBlocking {
        // 未到时间点（阈值传 24，任何时刻都算未到）且缓存能解开、里面
        // 有正在播的节目：直接用缓存，不刷新、不下载。地址写 127.0.0.1:9，
        // 一旦走刷新去下载立刻连接被拒抛错、用例失败
        val now = System.currentTimeMillis()
        File(tempFolder.root, "epg.json").writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = now - 1800_000, endAt = now + 1800_000, title = "缓存里的节目"))
                        )
                    )
                )
            )
        )

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 24,
            prefetchedXml = null,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result.first().channel)
        assertEquals("缓存里的节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList before threshold refreshes when cache has no live programme`() = runBlocking {
        // 未到时间点（阈值传 24）、缓存能解开但里面全是播完的节目
        // （主源过期只剩昨天数据）：沿用它的话 0 点到阈值之间节目单
        // 一直是昨天的；没正在播的节目就当过期、直接走刷新，备用源
        // 有正在播的节目就用备用源
        val now = System.currentTimeMillis()
        File(tempFolder.root, "epg.json").writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = now - 7200_000, endAt = now - 3600_000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        val backupXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 1800_000)}" stop="${formatEpgTime(now + 1800_000)}" channel="cctv1"><title>备用源正在播</title></programme>
            </tv>
        """.trimIndent()
        File(tempFolder.root, "epg.xml").writeText(backupXml)

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 24,
            prefetchedXml = null,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("备用源正在播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList before threshold refreshes from prefetched xml when cache has no live programme`() = runBlocking {
        // 未到时间点（阈值传 24）、缓存能解开但里面全是播完的节目：
        // ec40633 之前这里直接沿用旧缓存，0 点到阈值之间一直显示
        // 昨天的节目；现在没正在播的节目就当过期、直接走刷新。预
        // 下载的 XML 里有正在播的节目就用它——ec40633 自己的用例
        // 只锁了“备用源兜底”那条路，预下载这条路一次也没断言过；
        // 一旦改回旧逻辑，这条用例会直接返回旧缓存的“旧节目”
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = now - 7200_000, endAt = now - 3600_000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 30 * 60 * 1000L)}" stop="${formatEpgTime(now + 30 * 60 * 1000L)}" channel="cctv1"><title>正在播的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 24,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("正在播的节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList before threshold refreshes when cache is undecodable`() = runBlocking {
        // 未到时间点但缓存解不开（写半截 JSON 模拟截断）：不等时间点，
        // 直接走刷新。没修之前这里会落空——getOrRefresh 只按日期判
        // “未过期”，把半截缓存原样返回、解码失败给空节目单，要等隔
        // 天才恢复；修完后同天解不开也算过期、刷新走预下载
        File(tempFolder.root, "epg.json").writeText("{}")

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="20261113080000 +0800" stop="20261113090000 +0800" channel="cctv1"><title>新闻联播</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 24,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList falls back to stale cache when refresh fails`() = runBlocking {
        // 刷新失败时旧缓存顶上：缓存是昨天的（过期、必须刷新），预下载
        // 没给、地址又连不上（127.0.0.1:9 丢弃端口）——刷新抛错后不
        // 应直接报错，而是返回旧缓存那台；时间戳不重写，下次加载仍会
        // 再试刷新
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = 1000, endAt = 2000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = null,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-2", result.first().channel)
        assertEquals("旧节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList returns empty epg when stale cache is undecodable and refresh fails`() = runBlocking {
        // 旧缓存是半截 JSON（解不开）、又是昨天的（过期、必须刷新），
        // 刷新又连不上（127.0.0.1:9 丢弃端口）：刷新失败时 getOrRefresh
        // 会拿半截旧缓存顶上，解码失败后给空节目单、不抛错——和“没
        // 缓存可顶时原样抛错”是两条路，别混了
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText("""[{"channel":"CCTV-1"""")
        cacheFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = null,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(0, result.size)
    }

    @Test
    fun `hasLiveProgramme is true when a programme is live now`() {
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 1000, endAt = now + 1000, title = "正在播"))
                    )
                )
            )
        )

        assertTrue(hasLiveProgramme(list, now))
    }

    @Test
    fun `hasLiveProgramme is false when all programmes already ended`() {
        // 凌晨拉到的旧缓存就是这样：文件日期是今天，节目全是昨天的，
        // 白天再看一档正在播的都没有
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 2000, endAt = now - 1000, title = "播完了"))
                    )
                )
            )
        )

        assertFalse(hasLiveProgramme(list, now))
    }

    @Test
    fun `hasLiveProgramme is false for future-only programmes`() {
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now + 1000, endAt = now + 2000, title = "还没开始"))
                    )
                )
            )
        )

        assertFalse(hasLiveProgramme(list, now))
    }

    @Test
    fun `hasLiveProgramme is false for empty list and null`() {
        val now = System.currentTimeMillis()

        assertFalse(hasLiveProgramme(EpgList(), now))
        assertFalse(hasLiveProgramme(null, now))
    }

    @Test
    fun `hasLiveProgramme is true when any channel has a live programme`() {
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 2000, endAt = now - 1000, title = "播完了"))
                    )
                ),
                Epg(
                    channel = "CCTV-2",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 1000, endAt = now + 1000, title = "正在播"))
                    )
                ),
            )
        )

        assertTrue(hasLiveProgramme(list, now))
    }

    @Test
    fun `hasLiveProgramme is true when a programme starts exactly now`() {
        // 左闭：开始时间等于 now 算正在播
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now, endAt = now + 1000, title = "刚开始"))
                    )
                )
            )
        )

        assertTrue(hasLiveProgramme(list, now))
    }

    @Test
    fun `hasLiveProgramme is false when a programme ends exactly now`() {
        // 右开：结束时间等于 now 算已播完
        val now = System.currentTimeMillis()
        val list = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1",
                    programmes = EpgProgrammeList(
                        listOf(EpgProgramme(startAt = now - 2000, endAt = now, title = "刚播完"))
                    )
                )
            )
        )

        assertFalse(hasLiveProgramme(list, now))
    }

    @Test
    fun `getEpgList refreshes when today cache has no live programme`() = runBlocking {
        // 回归：凌晨拉到的旧缓存（文件是今天的、内容是昨天的）以前会
        // 被当成未过期沿用一整天，节目单一直显示无节目；现在没正在
        // 播的节目就算过期，走预下载的 XML 重新拉取
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(
                                EpgProgramme(
                                    startAt = now - 25 * 60 * 60 * 1000L,
                                    endAt = now - 24 * 60 * 60 * 1000L,
                                    title = "旧节目",
                                )
                            )
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1 综合</display-name></channel>
              <programme start="${formatEpgTime(now - 30 * 60 * 1000L)}" stop="${formatEpgTime(now + 30 * 60 * 1000L)}" channel="cctv1"><title>正在播的节目</title></programme>
            </tv>
        """.trimIndent()

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("正在播的节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList uses today cache when it has a live programme`() = runBlocking {
        // 反向锁死：有正在播的节目时仍按日期规则用缓存，不刷新。
        // 地址写 127.0.0.1:9，一旦走刷新去下载立刻连接被拒抛错、
        // 用例失败；走缓存时地址碰都不碰，单测全程不联网
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(
                                EpgProgramme(
                                    startAt = now - 30 * 60 * 1000L,
                                    endAt = now + 30 * 60 * 1000L,
                                    title = "缓存里的直播",
                                )
                            )
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now)

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = null,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result.first().channel)
        assertEquals("缓存里的直播", result.first().programmes.first().title)
    }

    @Test
    fun `prefetch returns null when download fails`() = runBlocking {
        // 下载失败也返回 null（正常路径会重试并回落旧缓存），不抛错。
        // JSON 缓存是昨天的、里面没正在播的节目 → 走到下载那条路；
        // 地址 127.0.0.1:9（discard 端口、无人监听）→ 连接被拒 →
        // 返回 null。之前只有两条“不用下载”分支有单测，下载那条路
        // 一次也没断言过
        val now = System.currentTimeMillis()
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-2",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = now - 2000, endAt = now - 1000, title = "旧节目"))
                        )
                    )
                )
            )
        )
        cacheFile.setLastModified(now - 25 * 60 * 60 * 1000L)

        val result = EpgRepository().downloadEpgXmlIfNeeded("http://127.0.0.1:9/epg.xml", 0)

        assertNull(result)
    }

    @Test
    fun `getEpgList throws when refresh fails without cache`() {
        // 没缓存可顶时刷新失败就原样抛错，不给空节目单冒充成功。
        // 壳 852 轮姊妹检查：壳那边断言了错误文案，天光这边原来只
        // 断言抛了错、没断言文案—— raw 的连接错漏出来、或包错文案
        // 时也照样通过；文案是用户看得到的“请检查网络连接”，该钉死。
        // 文案与壳不同（天光是“获取远程节目单xml失败”，壳是“获取
        // 节目单xml失败”），是天光自己的措辞，不是写错了
        val ex = assertThrows(Exception::class.java) {
            runBlocking {
                EpgRepository().getEpgList(
                    "http://127.0.0.1:9/epg.xml",
                    refreshTimeThreshold = 0,
                    prefetchedXml = null,
                    backupXmlUrl = "http://127.0.0.1:9/backup.xml",
                )
            }
        }
        assertEquals("获取远程节目单xml失败，请检查网络连接", ex.message)
    }

    @Test
    fun `getEpgList throws HttpStatusException as-is when both sources return 404`() {
        // 服务器回了明确状态码就原样抛：不是网络问题，别包成“请检查网络
        // 连接”误导用户去查网络。主源 404 后 loadEpgList 会试备用源，
        // 备用源也指到同一台假服务器的 404 路径，抛出来的才是主源那
        // 个 HttpStatusException 本身——fetchXml 的 catch 里那行
        // `if (ex is HttpStatusException) throw ex` 删了也不会红之前，
        // 这档一次也没断言过；连不上那档已有用例（包成中文错）。
        // 没缓存可顶：getOrRefresh 刷新失败时没旧缓存可回落，原样抛出来。
        val server = TestStatusHttpServer().apply { start() }
        try {
            val ex = assertThrows(HttpStatusException::class.java) {
                runBlocking {
                    EpgRepository().getEpgList(
                        "http://127.0.0.1:${server.port}/notfound.xml",
                        refreshTimeThreshold = 0,
                        prefetchedXml = null,
                        backupXmlUrl = "http://127.0.0.1:${server.port}/notfound.xml",
                    )
                }
            }
            assertEquals(404, ex.code)
            assertTrue(ex.message?.contains("服务器返回 404") == true)
            assertFalse(ex.message?.contains("请检查网络连接") == true)
            // 壳 1012 轮姊妹检查：它那边单源断“只请求一次”，天光是主备
            // 两源——主源 1 次＋备用源 1 次、共 2 次才是“状态错不重试”。
            // fetchXml 与 getOrRefresh 里都没有重试循环，今天不断次数，
            // 以后谁加了重试也不会红，只断异常等于没钉死这档。
            assertEquals(2, server.hits.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `getEpgList throws HttpStatusException as-is when both sources return 500`() {
        // 与 404 同款：500 也要原样抛，码值不同就是两档（2249 轮同款教训：
        // 错误映射要按码值数齐，404 钉了不等于 500 钉了；2250 轮同款教训：
        // 仓库层与下载器是两处独立的生产代码，节目单这处 fetchXml 是
        // 第四处，前面三处补了不等于这处也补了）
        val server = TestStatusHttpServer().apply { start() }
        try {
            val ex = assertThrows(HttpStatusException::class.java) {
                runBlocking {
                    EpgRepository().getEpgList(
                        "http://127.0.0.1:${server.port}/servererror.xml",
                        refreshTimeThreshold = 0,
                        prefetchedXml = null,
                        backupXmlUrl = "http://127.0.0.1:${server.port}/servererror.xml",
                    )
                }
            }
            assertEquals(500, ex.code)
            assertTrue(ex.message?.contains("服务器返回 500") == true)
            assertFalse(ex.message?.contains("请检查网络连接") == true)
            // 与 404 同款：主源 1 次＋备用源 1 次、共 2 次，不重试。
            // 500 在天光是“服务器一时的问题”分档（重试判定在播放器层），
            // 节目单拉取层本身没有重试循环，别把“只请求两次”误读成
            // “500 不该重试”——这档只钉死拉取层不自己重试。
            assertEquals(2, server.hits.get())
        } finally {
            server.stop()
        }
    }
}
