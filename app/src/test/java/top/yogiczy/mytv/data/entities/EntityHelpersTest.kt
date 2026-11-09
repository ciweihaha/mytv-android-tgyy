package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx

class EntityHelpersTest {
    private fun programme(startOffsetMs: Long, endOffsetMs: Long, title: String): EpgProgramme {
        val now = System.currentTimeMillis()
        return EpgProgramme(
            startAt = now + startOffsetMs,
            endAt = now + endOffsetMs,
            title = title,
        )
    }

    @Test
    fun `currentProgrammes returns now and next`() {
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    programme(-7200_000, -3600_000, "过去"),
                    programme(-1800_000, 1800_000, "正在播"),
                    programme(1800_000, 5400_000, "稍后"),
                )
            ),
        )

        val current = epg.currentProgrammes()
        assertEquals("正在播", current?.now?.title)
        assertEquals("稍后", current?.next?.title)
    }

    @Test
    fun `currentProgrammes returns null when nothing is live`() {
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    programme(-7200_000, -3600_000, "过去1"),
                    programme(-3600_000, -1800_000, "过去2"),
                )
            ),
        )
        assertNull(epg.currentProgrammes())
    }

    @Test
    fun `currentProgrammes returns null next when live programme is last`() {
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    programme(-7200_000, -3600_000, "过去"),
                    programme(-1800_000, 1800_000, "正在播"),
                )
            ),
        )

        val current = epg.currentProgrammes()
        assertEquals("正在播", current?.now?.title)
        assertNull(current?.next)
    }

    @Test
    fun `currentProgrammes returns null for empty programme list`() {
        assertNull(Epg(channel = "cctv1").currentProgrammes())
    }

    @Test
    fun `iptvIdx returns global index across groups`() {
        val target = Iptv(name = "目标", channelName = "target")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "分组1",
                    iptvList = IptvList(List(3) { Iptv(name = "频道1-$it") }),
                ),
                IptvGroup(
                    name = "分组2",
                    iptvList = IptvList(listOf(Iptv(name = "频道2-0"), target)),
                ),
            )
        )

        assertEquals(4, groupList.iptvIdx(target))
        assertEquals(0, groupList.iptvIdx(Iptv(name = "频道1-0")))
    }

    @Test
    fun `iptvIdx returns minus one when channel is missing`() {
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "分组1", iptvList = IptvList(listOf(Iptv(name = "A")))))
        )
        assertEquals(-1, groupList.iptvIdx(Iptv(name = "不存在")))
        assertEquals(-1, IptvGroupList().iptvIdx(Iptv(name = "不存在")))
    }
}
