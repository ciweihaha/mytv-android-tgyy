package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
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
    fun `currentProgrammes picks next programme at exact boundary`() {
        // 首尾相接：上一档结束正好等于下一档开始的那一刻，正在播
        // 的是下一档（左闭右开的组合语义）；传固定时间才能钉死
        // 这个边界——无参调 currentProgrammes() 时 wall clock
        // 会走动，钉不住
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now - 10_000, endAt = now, title = "上一档"),
                    EpgProgramme(startAt = now, endAt = now + 10_000, title = "下一档"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertEquals("下一档", current?.now?.title)
        assertNull(current?.next)
    }

    @Test
    fun `currentProgrammes returns null when last programme ends exactly now`() {
        // 最后一档结束正好等于现在：已播完、不算正在播，也没有
        // 下一档，整份回 null
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now - 10_000, endAt = now, title = "刚播完"),
                )
            ),
        )

        assertNull(epg.currentProgrammes(now))
    }

    @Test
    fun `currentProgrammes falls back to overlapping future programme as next`() {
        // 脏数据：下一档与正在播的重叠（开始在现在之后、结束在当前
        // 档结束之前），nextAfterEnd 找不到，退一步取还没开始的
        // 最早那档；传固定时间才能钉死这条退路
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now, endAt = now + 20_000, title = "正在播"),
                    EpgProgramme(startAt = now + 10_000, endAt = now + 15_000, title = "重叠的下一档"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertEquals("正在播", current?.now?.title)
        assertEquals("重叠的下一档", current?.next?.title)
    }

    @Test
    fun `currentProgrammes prefers programme starting after current ends over overlapping future`() {
        // 正在播 + 重叠档（开始在当前结束之前）+ 正点下一档（开始正好
        // 等于当前结束）：下一档应是正点那档，不是重叠那档——
        // nextAfterEnd 非空时不走 earliestFuture 退路；两者重合的
        // 旧用例分不出这一档
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now, endAt = now + 20_000, title = "正在播"),
                    EpgProgramme(startAt = now + 10_000, endAt = now + 15_000, title = "重叠档"),
                    EpgProgramme(startAt = now + 20_000, endAt = now + 30_000, title = "正点下一档"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertEquals("正在播", current?.now?.title)
        assertEquals("正点下一档", current?.next?.title)
    }

    @Test
    fun `currentProgrammes returns null for empty programme list`() {
        assertNull(Epg(channel = "cctv1").currentProgrammes())
    }

    @Test
    fun `currentProgrammes returns next only when nothing is live yet`() {
        // 还没到当天首档节目：正在播是空、稍后播放带上最近的那档；
        // 若这条退路丢了，面板两行都会显示“无节目”
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now - 20_000, endAt = now - 10_000, title = "已播完"),
                    EpgProgramme(startAt = now + 10_000, endAt = now + 20_000, title = "即将开始"),
                    EpgProgramme(startAt = now + 30_000, endAt = now + 40_000, title = "再晚点"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertNull(current?.now)
        assertEquals("即将开始", current?.next?.title)
    }

    @Test
    fun `currentProgrammes picks earliest future regardless of list order`() {
        // 节目单顺序不保证按时间排：列表里晚的在前、早的在后，稍后播放
        // 仍应是开始最早的那档
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now + 30_000, endAt = now + 40_000, title = "晚的"),
                    EpgProgramme(startAt = now + 10_000, endAt = now + 20_000, title = "早的"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertNull(current?.now)
        assertEquals("早的", current?.next?.title)
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

    @Test
    fun `iptvAt returns channel by global index across groups`() {
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "分组1",
                    iptvList = IptvList(List(3) { Iptv(name = "频道1-$it") }),
                ),
                IptvGroup(
                    name = "空分组",
                    iptvList = IptvList(emptyList()),
                ),
                IptvGroup(
                    name = "分组2",
                    iptvList = IptvList(listOf(Iptv(name = "频道2-0"), Iptv(name = "频道2-1"))),
                ),
            )
        )

        assertEquals("频道1-0", groupList.iptvAt(0)?.name)
        assertEquals("频道1-2", groupList.iptvAt(2)?.name)
        // 空分组不占下标：分组2 的首台仍是全局第 3 个
        assertEquals("频道2-0", groupList.iptvAt(3)?.name)
        assertEquals("频道2-1", groupList.iptvAt(4)?.name)
    }

    @Test
    fun `iptvAt returns null for out of range index`() {
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "分组1", iptvList = IptvList(listOf(Iptv(name = "A")))))
        )
        assertNull(groupList.iptvAt(1))
        assertNull(groupList.iptvAt(-1))
        assertNull(IptvGroupList().iptvAt(0))
    }

    @Test
    fun `iptvList flattens groups in order and is built only once`() {
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "分组1",
                    iptvList = IptvList(List(3) { Iptv(name = "频道1-$it") }),
                ),
                IptvGroup(
                    name = "空分组",
                    iptvList = IptvList(emptyList()),
                ),
                IptvGroup(
                    name = "分组2",
                    iptvList = IptvList(listOf(Iptv(name = "频道2-0"), Iptv(name = "频道2-1"))),
                ),
            )
        )

        assertEquals(
            listOf("频道1-0", "频道1-1", "频道1-2", "频道2-0", "频道2-1"),
            groupList.iptvList.map { it.name },
        )
        // 多次访问拿的是同一份，不再每次重新摊平一份新列表
        assertSame(groupList.iptvList, groupList.iptvList)
        assertEquals(emptyList<Iptv>(), IptvGroupList().iptvList)
    }

    @Test
    fun `currentProgrammes picks first on tied future start times`() {
        // 两个未来节目开始时间完全相同：稍后播放应取列表里先写的那档
        // （循环里用严格小于比较，平局时先写的留下来）；改成小于等
        // 于时后写的那档会顶掉先写的，这里会失败
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now + 10_000, endAt = now + 20_000, title = "先写的"),
                    EpgProgramme(startAt = now + 10_000, endAt = now + 30_000, title = "后写的"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertNull(current?.now)
        assertEquals("先写的", current?.next?.title)
    }

    @Test
    fun `currentProgrammes picks first on tied next start times`() {
        // 正在播 + 两档下一档开始时间都正好等于当前档结束：下一档应
        // 取列表里先写的那档（nextAfterEnd 也是严格小于比较，平局
        // 取第一个）；改成小于等于时后写的那档会顶掉先写的
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now, endAt = now + 20_000, title = "正在播"),
                    EpgProgramme(startAt = now + 20_000, endAt = now + 30_000, title = "先写的下一档"),
                    EpgProgramme(startAt = now + 20_000, endAt = now + 40_000, title = "后写的下一档"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertEquals("正在播", current?.now?.title)
        assertEquals("先写的下一档", current?.next?.title)
    }

    @Test
    fun `currentProgrammes picks first when two programmes are live at once`() {
        // 壳 849 轮姊妹检查：两档节目开始时间相同、同时都在播，正在播
        // 取先写的那档（循环里 current 只在空时才赋值，后写的顶不掉
        // 先写的）；1989 轮只钉了下一档的两档平局，正在播这档漏了——
        // 节目单时间有重叠的脏数据时，正在播取哪档本来是没钉死的
        val now = 1_000_000L
        val epg = Epg(
            channel = "cctv1",
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(startAt = now - 10_000, endAt = now + 10_000, title = "先写的"),
                    EpgProgramme(startAt = now - 10_000, endAt = now + 20_000, title = "后写的"),
                )
            ),
        )

        val current = epg.currentProgrammes(now)
        assertEquals("先写的", current?.now?.title)
        assertNull(current?.next)
    }
}
