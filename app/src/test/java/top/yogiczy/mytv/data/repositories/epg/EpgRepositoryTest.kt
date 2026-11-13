package top.yogiczy.mytv.data.repositories.epg

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import java.io.File

class EpgRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
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
        // JSON 缓存是今天的：调用方直接用缓存，不下载。阈值传 0 跳过
        // “未到时间点”那条分支，只走“缓存是今天”这一条；只断言不
        // 下载，不碰网络
        val cacheFile = File(tempFolder.root, "epg.json")
        cacheFile.writeText(
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
        cacheFile.setLastModified(System.currentTimeMillis())

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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新闻联播", result.first().programmes.first().title)
    }
}
