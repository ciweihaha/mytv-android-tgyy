package top.yogiczy.mytv.data.repositories.epg

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
}
