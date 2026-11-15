package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.channelNoText
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.favoriteIptvList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvGroupIdx

class IptvGroupListTest {
    @Test
    fun `台号是下标加一补零到两位`() {
        val cctv1 = Iptv(name = "CCTV-1", channelName = "cctv1")
        val cctv2 = Iptv(name = "CCTV-2", channelName = "cctv2")
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "央视", iptvList = IptvList(listOf(cctv1, cctv2))))
        )

        assertEquals("01", groupList.channelNoText(cctv1))
        assertEquals("02", groupList.channelNoText(cctv2))
    }

    @Test
    fun `不在列表里的台显示横线而不是00`() {
        // 空源的占位频道不在列表里：原来下标 -1 加 1 得 0，右上角
        // 显示不存在的“00”；现在显示“–”
        val cctv1 = Iptv(name = "CCTV-1", channelName = "cctv1")
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "央视", iptvList = IptvList(listOf(cctv1))))
        )

        assertEquals("–", groupList.channelNoText(Iptv()))
        assertEquals("–", IptvGroupList().channelNoText(cctv1))
    }

    @Test
    fun `同一台在两个分组里出现时收藏夹只留一个`() {
        // 同一台（名字、频道名、地址全一样）在两个分组里都出现：
        // 原来收藏夹里会是两个完全相同的频道对象，列表按频道对象设
        // 唯一键会撞键直接崩掉
        val cctv1 = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf("http://a.com/1.m3u8"),
        )
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "央视", iptvList = IptvList(listOf(cctv1))),
                IptvGroup(name = "全部", iptvList = IptvList(listOf(cctv1))),
            )
        )

        val favorites = groupList.favoriteIptvList(listOf("cctv1"))
        assertEquals(1, favorites.size)
        assertEquals(cctv1, favorites[0])
    }

    @Test
    fun `同名不同地址的台各留各的`() {
        val first = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf("http://a.com/1.m3u8"),
        )
        val second = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf("http://b.com/1.m3u8"),
        )
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "央视", iptvList = IptvList(listOf(first))),
                IptvGroup(name = "全部", iptvList = IptvList(listOf(second))),
            )
        )

        assertEquals(2, groupList.favoriteIptvList(listOf("cctv1")).size)
    }

    @Test
    fun `没收藏的台和不在源里的收藏名都不进收藏夹`() {
        val cctv1 = Iptv(name = "CCTV-1", channelName = "cctv1")
        val cctv2 = Iptv(name = "CCTV-2", channelName = "cctv2")
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "央视", iptvList = IptvList(listOf(cctv1, cctv2))))
        )

        val favorites = groupList.favoriteIptvList(listOf("cctv1", "不存在的台"))
        assertEquals(1, favorites.size)
        assertEquals(cctv1, favorites[0])
    }

    @Test
    fun `台号两位与三位边界`() {
        // 壳线 774 轮同款：补零只补到两位——第 10 个台是“10”不是“010”，
        // 第 100 个台是“100”、补零不截断；旧用例只测过“01”“02”。
        val channels = List(100) { Iptv(name = "CCTV-$it", channelName = "cctv$it") }
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "央视", iptvList = IptvList(channels)))
        )

        assertEquals("10", groupList.channelNoText(channels[9]))
        assertEquals("99", groupList.channelNoText(channels[98]))
        assertEquals("100", groupList.channelNoText(channels[99]))
    }

    @Test
    fun `iptvGroupIdx returns group index of channel`() {
        // 分组下标全程没单测：经典面板与分组列表两处调用点都包着
        // max(0, …)，但下标本身（第几组、找不到回 -1）没人钉过
        val target = Iptv(name = "目标", channelName = "target")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "分组1", iptvList = IptvList(listOf(Iptv(name = "A")))),
                IptvGroup(name = "分组2", iptvList = IptvList(listOf(Iptv(name = "B"), target))),
            )
        )

        assertEquals(0, groupList.iptvGroupIdx(Iptv(name = "A")))
        assertEquals(1, groupList.iptvGroupIdx(target))
    }

    @Test
    fun `iptvGroupIdx returns minus one when channel is missing`() {
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "分组1", iptvList = IptvList(listOf(Iptv(name = "A")))))
        )

        assertEquals(-1, groupList.iptvGroupIdx(Iptv(name = "不存在")))
        assertEquals(-1, IptvGroupList().iptvGroupIdx(Iptv(name = "不存在")))
    }
}
