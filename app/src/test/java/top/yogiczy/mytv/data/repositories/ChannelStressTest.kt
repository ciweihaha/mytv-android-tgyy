package top.yogiczy.mytv.data.repositories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.searchIptv
import top.yogiczy.mytv.data.entities.recordRecentChannel
import top.yogiczy.mytv.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.data.repositories.epg.buildEpgFilterSet
import top.yogiczy.mytv.data.repositories.epg.keepEpgChannelNormalized
import top.yogiczy.mytv.data.repositories.epg.parseEpgTimeFallback
import top.yogiczy.mytv.data.repositories.iptv.parser.M3uIptvParser
import top.yogiczy.mytv.data.repositories.iptv.parser.TvboxIptvParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 压力测试：反复换台、大量频道 EPG 加载、长时间播放对应的数据层负载，
 * 全部在 JVM 单测里跑（无真机、无联网）。时间上限都放得很宽——单测机
 * 器快慢不一，断言只拦“数量级不对”的退化，不拦几百毫秒的抖动；
 * 真机上的播放、换台手感与长时间播放，仍需平板回来后实测。
 */
class ChannelStressTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    private fun formatEpgTime(millis: Long): String =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).format(Date(millis))

    /** 2000 个频道、20 个分组的 M3U：每台 2 条线路、带 tvg-id 与分组 */
    private fun buildLargeM3u(channelCount: Int = 2000, groupCount: Int = 20): String =
        buildString {
            append("#EXTM3U\n")
            for (i in 0 until channelCount) {
                val group = "分组${i % groupCount}"
                append("#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"频道$i\" group-title=\"$group\",频道$i\n")
                append("http://example.com/$i/1.m3u8\n")
                append("#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"频道$i\" group-title=\"$group\",频道$i\n")
                append("http://example.com/$i/2.m3u8\n")
            }
        }

    /** 2000 个频道、20 个分组的 tvbox 源 */
    private fun buildLargeTvbox(channelCount: Int = 2000, groupCount: Int = 20): String =
        buildString {
            for (g in 0 until groupCount) {
                append("分组$g,#genre#\n")
                for (i in 0 until channelCount / groupCount) {
                    val idx = g * (channelCount / groupCount) + i
                    append("频道$idx,http://example.com/$idx/1.m3u8#http://example.com/$idx/2.m3u8\n")
                }
            }
        }

    /** 500 个频道 × 20 档节目 = 10000 档节目的 EPG，其中一档正在播 */
    private fun buildLargeEpg(channelCount: Int = 500, programmesPerChannel: Int = 20): String {
        val now = System.currentTimeMillis()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            for (c in 0 until channelCount) {
                append("<channel id=\"ch$c\"><display-name>频道$c</display-name></channel>\n")
            }
            for (c in 0 until channelCount) {
                for (p in 0 until programmesPerChannel) {
                    val start = now - 3600_000L + p * 1800_000L
                    append("<programme start=\"${formatEpgTime(start)}\" stop=\"${formatEpgTime(start + 1800_000L)}\" channel=\"ch$c\"><title>节目$c-$p</title></programme>\n")
                }
            }
            append("</tv>\n")
        }
    }

    @Test
    fun `parsing 2000-channel m3u finishes fast and keeps all channels`() = runBlocking {
        val data = buildLargeM3u()
        val start = System.currentTimeMillis()
        val result = M3uIptvParser().parse(data)
        val elapsed = System.currentTimeMillis() - start
        println("M3U 2000 频道解析耗时：${elapsed}ms")

        assertEquals(20, result.size)
        assertEquals(2000, result.sumOf { it.iptvList.size })
        // 同名两条地址并进同一频道的 urlList
        assertEquals(2, result.first().iptvList.first().urlList.size)
        assertTrue("2000 频道 M3U 解析超过 10 秒：${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `parsing 2000-channel tvbox finishes fast and keeps all channels`() = runBlocking {
        val data = buildLargeTvbox()
        val start = System.currentTimeMillis()
        val result = TvboxIptvParser().parse(data)
        val elapsed = System.currentTimeMillis() - start
        println("tvbox 2000 频道解析耗时：${elapsed}ms")

        assertEquals(20, result.size)
        assertEquals(2000, result.sumOf { it.iptvList.size })
        assertEquals(2, result.first().iptvList.first().urlList.size)
        assertTrue("2000 频道 tvbox 解析超过 10 秒：${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `parsing 500-channel 10000-programme epg finishes fast`() = runBlocking {
        val xml = buildLargeEpg()
        val start = System.currentTimeMillis()
        val result = EpgRepository().getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )
        val elapsed = System.currentTimeMillis() - start
        println("EPG 500 频道 × 20 档解析耗时：${elapsed}ms")

        assertEquals(500, result.size)
        assertEquals(20, result.first().programmes.size)
        assertEquals(10000, result.sumOf { it.programmes.size })
        assertTrue("10000 档 EPG 解析超过 15 秒：${elapsed}ms", elapsed < 15_000)
    }

    @Test
    fun `rapid channel switching over 2000 channels stays correct and fast`() = runBlocking {
        val groupList = M3uIptvParser().parse(buildLargeM3u())
        val total = groupList.sumOf { it.iptvList.size }
        assertEquals(2000, total)

        // 模拟遥控器连按 5000 次上下键：步长 7（与 2000 互质，走遍全表）、
        // 来回绕圈，每步都按下标取台、再反查下标，两边必须对得上。
        // 注意 M3U 解析后频道按分组重排，全局下标对应的频道名不再是
        // “频道$idx”，只断言下标往返一致，不按名字断言
        var idx = 0
        val start = System.currentTimeMillis()
        repeat(5000) {
            idx = (idx + 7) % total
            val iptv = groupList.iptvAt(idx)
            assertTrue(iptv != null)
            assertEquals(idx, groupList.iptvIdx(iptv!!))
        }
        val elapsed = System.currentTimeMillis() - start
        println("5000 次换台下标往返耗时：${elapsed}ms")
        assertTrue("5000 次换台超过 10 秒：${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `searching 2000 channels repeatedly stays fast`() = runBlocking {
        val groupList = M3uIptvParser().parse(buildLargeM3u())

        val start = System.currentTimeMillis()
        repeat(200) { round ->
            val result = groupList.searchIptv("频道${round % 100}")
            assertTrue(result.isNotEmpty())
        }
        val elapsed = System.currentTimeMillis() - start
        println("200 次搜索（2000 频道）耗时：${elapsed}ms")
        // “频道1” 命中 频道1、频道10-19、频道100-199、频道1000-1999 共 1111 个
        assertEquals(1111, groupList.searchIptv("频道1").size)
        assertTrue("200 次搜索超过 10 秒：${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `epg channel filter with 2000-entry filter set stays fast`() {
        // 归一化集合版：500 个频道 × 2000 项过滤集，旧线性扫是 100 万次
        // 短串比较，现在是 1000 次哈希查找
        val filter = buildEpgFilterSet((0 until 2000).map { "ch$it" })
        val start = System.currentTimeMillis()
        repeat(500) { i ->
            assertTrue(keepEpgChannelNormalized("ch$i", "频道$i", filter))
        }
        val elapsed = System.currentTimeMillis() - start
        println("500 次归一化过滤判断（2000 项过滤集）耗时：${elapsed}ms")
        assertTrue("500 次过滤判断超过 5 秒：${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `recording 50 channel switches keeps only last 10 in order`() {
        var recent: List<String> = emptyList()
        repeat(50) { i ->
            recent = recordRecentChannel(recent, "频道$i")
        }
        assertEquals(10, recent.size)
        assertEquals("频道49", recent.first())
        assertEquals("频道40", recent.last())
    }

    @Test
    fun `parsing the same large m3u twice gives identical results`() = runBlocking {
        // 解析必须是确定性的：同一份源两次解析，分组顺序、频道顺序、
        // 线路顺序都一字不变，否则换源/刷新后台号会漂移
        val data = buildLargeM3u()
        val first = M3uIptvParser().parse(data)
        val second = M3uIptvParser().parse(data)
        assertEquals(first, second)
    }

    @Test
    fun `parsing large sources concurrently gives the same results as sequential`() = runBlocking {
        // 换源刷新、节目单刷新、面板打开可能同时触发解析：解析器是
        // 无状态挂起函数，多路并发跑同一份大源，结果必须和串行一字
        // 不差；解析里没有共享可变状态可写坏，并发只拼 CPU。
        // 8 路共用同一个解析器实例：只各建新实例测不到“无共享可变
        // 状态”这一点——状态全是 parse() 里的局部变量，伴生对象只
        // 有不可变的正则与字符集，共用实例的结果也必须和串行一致
        val m3uData = buildLargeM3u()
        val tvboxData = buildLargeTvbox()
        val m3uParser = M3uIptvParser()
        val tvboxParser = TvboxIptvParser()
        val sequentialM3u = m3uParser.parse(m3uData)
        val sequentialTvbox = tvboxParser.parse(tvboxData)

        val results = withContext(Dispatchers.Default) {
            (0 until 4).map { async { m3uParser.parse(m3uData) } } +
                (0 until 4).map { async { tvboxParser.parse(tvboxData) } }
        }.awaitAll()

        assertEquals(8, results.size)
        results.take(4).forEach { assertEquals(sequentialM3u, it) }
        results.drop(4).forEach { assertEquals(sequentialTvbox, it) }
    }

    @Test
    fun `parsing the same large epg concurrently gives identical results`() = runBlocking {
        // 节目单主源与备用源、缓存刷新与面板读取可能同时解析同一份
        // XML：解析跑在 Dispatchers.Default 线程池里，多路并发下
        // 每路的节目数、正在播那档都必须和串行一致。缓存文件是共享
        // 的：多路并发写走原子改名，最后落盘的那份永远是完整的。
        // 8 路共用同一个仓库实例：只各建新实例测不到“仓库实例本身
        // 无共享可变状态”这一点——实例字段只有 log（本身无状态，
        // 共享的日志历史在同一把锁里同步写）与各建的 EpgXmlRepository
        //（字段也只有 log），FileCacheRepository 里也没有可变实例
        // 状态；解析用的状态全是 parseFromXml 里的局部变量，时间
        // 格式器主路径每调用现建、备用解析按线程各存一份。
        // prefetchedXml 下内层 EpgXmlRepository 根本不会被碰到。
        val xml = buildLargeEpg()
        val repository = EpgRepository()
        val sequential = repository.getEpgList(
            "http://127.0.0.1:9/epg.xml",
            refreshTimeThreshold = 0,
            prefetchedXml = xml,
            backupXmlUrl = "http://127.0.0.1:9/backup.xml",
        )
        assertEquals(500, sequential.size)

        // 先清缓存再并发：否则 8 路全读到串行那次刚写好的缓存、根本
        // 没走到并发解析；清掉后每路都发现无缓存、各自解析再写回同
        // 一份 epg.json，内容相同、改名原子，结果必须和串行一字不差
        repository.clearCache()

        val results = withContext(Dispatchers.Default) {
            (0 until 8).map {
                async {
                    repository.getEpgList(
                        "http://127.0.0.1:9/epg.xml",
                        refreshTimeThreshold = 0,
                        prefetchedXml = xml,
                        backupXmlUrl = "http://127.0.0.1:9/backup.xml",
                    )
                }
            }
        }.awaitAll()

        assertEquals(8, results.size)
        results.forEach { assertEquals(sequential, it) }
    }

    @Test
    fun `epg time fallback parsing from many threads agrees`() = runBlocking {
        // 坏时间格式的源每条节目都走备用解析：四个 SimpleDateFormat
        // 是按线程各存一份复用的（SimpleDateFormat 不是线程安全的，
        // 跨线程共用会在并发下悄悄算错时间、节目贴错档）。16 路并
        // 发、各 500 次混合写法，算出的时刻必须和串行一字不差
        val samples = listOf(
            "20261112080000+0800" to 1794441600000L,
            "20261112080000 +08:00" to 1794441600000L,
            "20261112080000+08:00" to 1794441600000L,
            "20261112080000Z" to 1794470400000L,
            "20261112080000-0800" to 1794499200000L,
        )
        val expected = samples.map { (text, instant) -> text to instant }

        val results = withContext(Dispatchers.Default) {
            (0 until 16).map {
                async {
                    buildList {
                        repeat(100) {
                            expected.forEach { (text, _) -> add(parseEpgTimeFallback(text)) }
                        }
                    }
                }
            }
        }.awaitAll()

        assertEquals(16, results.size)
        results.forEach { parsed ->
            assertEquals(500, parsed.size)
            expected.forEachIndexed { index, (_, instant) ->
                repeat(100) { round ->
                    assertEquals(instant, parsed[round * expected.size + index])
                }
            }
        }
    }
}
