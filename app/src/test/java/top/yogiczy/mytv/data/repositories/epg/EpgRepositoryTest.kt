package top.yogiczy.mytv.data.repositories.epg

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals("新节目", result.first().programmes.first().title)
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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("新闻联播", result.first().programmes.first().title)
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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-2 综合", result.first().channel)
        assertEquals(1, result.first().programmes.size)
        assertEquals("另一台的节目", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList before threshold returns cache without refreshing`() = runBlocking {
        // 未到时间点（阈值传 24，任何时刻都算未到）且缓存能解开：直接
        // 用缓存，不刷新、不下载。地址写 127.0.0.1:9，一旦走刷新去
        // 下载立刻连接被拒抛错、用例失败
        File(tempFolder.root, "epg.json").writeText(
            Json.encodeToString(
                listOf(
                    Epg(
                        channel = "CCTV-1",
                        programmes = EpgProgrammeList(
                            listOf(EpgProgramme(startAt = 1000, endAt = 2000, title = "缓存里的节目"))
                        )
                    )
                )
            )
        )

        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 24,
            prefetchedXml = null,
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result.first().channel)
        assertEquals("缓存里的节目", result.first().programmes.first().title)
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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-2", result.first().channel)
        assertEquals("旧节目", result.first().programmes.first().title)
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
        )

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result.first().channel)
        assertEquals("缓存里的直播", result.first().programmes.first().title)
    }

    @Test
    fun `getEpgList throws when refresh fails without cache`() = runBlocking {
        // 没缓存可顶时刷新失败就原样抛错，不给空节目单冒充成功
        try {
            EpgRepository().getEpgList(
                "http://127.0.0.1:9/epg.xml",
                refreshTimeThreshold = 0,
                prefetchedXml = null,
            )
            fail("没缓存又刷新失败时应抛错")
        } catch (_: Exception) {
            // 预期：没旧缓存可顶，刷新失败原样抛错
        }
    }
}
