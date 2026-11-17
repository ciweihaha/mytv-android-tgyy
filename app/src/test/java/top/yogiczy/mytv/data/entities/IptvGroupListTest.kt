package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.channelNoText
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.favoriteIptvList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvGroupIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.recentIptvList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.searchIptv

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

    @Test
    fun `iptvIdx accumulates offsets across groups`() {
        // 全局下标是 iptvAt 与 channelNoText 的底座：iptvGroupIdx
        // 有单测，iptvIdx 本身一次也没直接断言过——跨分组的偏移累加
        // （第二组起的下标要加上前面各组的大小）只在 channelNoText
        // 里被间接用到，且旧用例全是单分组、偏移恒为 0
        val a = Iptv(name = "A")
        val b = Iptv(name = "B")
        val target = Iptv(name = "目标")
        val c = Iptv(name = "C")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "分组1", iptvList = IptvList(listOf(a, b))),
                IptvGroup(name = "空分组", iptvList = IptvList(emptyList())),
                IptvGroup(name = "分组3", iptvList = IptvList(listOf(target, c))),
            )
        )

        assertEquals(0, groupList.iptvIdx(a))
        assertEquals(1, groupList.iptvIdx(b))
        assertEquals(2, groupList.iptvIdx(target))
        assertEquals(3, groupList.iptvIdx(c))
        assertEquals(-1, groupList.iptvIdx(Iptv(name = "不存在")))
        assertEquals(-1, IptvGroupList().iptvIdx(a))
    }

    @Test
    fun `iptvAt returns channels by global index and null out of bounds`() {
        // 按全局下标取频道：上下键切台、输台号选台、启动恢复上次频道
        // 都调它——这函数一次也没断言过；下标越界（含负数）回 null
        // 是调用方兜底的依据，空分组不能把偏移算错
        val a = Iptv(name = "A")
        val b = Iptv(name = "B")
        val target = Iptv(name = "目标")
        val c = Iptv(name = "C")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "分组1", iptvList = IptvList(listOf(a, b))),
                IptvGroup(name = "空分组", iptvList = IptvList(emptyList())),
                IptvGroup(name = "分组3", iptvList = IptvList(listOf(target, c))),
            )
        )

        assertEquals(a, groupList.iptvAt(0))
        assertEquals(b, groupList.iptvAt(1))
        assertEquals(target, groupList.iptvAt(2))
        assertEquals(c, groupList.iptvAt(3))
        assertEquals(null, groupList.iptvAt(4))
        assertEquals(null, groupList.iptvAt(-1))
        assertEquals(null, IptvGroupList().iptvAt(0))
    }

    @Test
    fun `iptvList is built once and reused on repeated access`() {
        // 全量频道列表第一次访问时建好、之后直接拿同一份：原来是扩展属性，
        // 每访问一次就把全部分组重新摊平成一份新列表；千级频道源下临时面板、
        // 节目单刷新几处按需调用的地方每次都重复分配。改成 lazy 后重复访问
        // 不该再分配——这档只断内容对不对是测不出来的
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "央视", iptvList = IptvList(listOf(Iptv(name = "CCTV-1")))))
        )

        assertSame(groupList.iptvList, groupList.iptvList)
        assertEquals(1, groupList.iptvList.size)
    }

    @Test
    fun `iptvIdx finds channel across groups and returns minus one when missing`() {
        // 索引表版 iptvIdx：跨分组、重复台只认第一次出现的位置、找不
        // 到回 -1，和原来逐组扫的语义一致
        val target = Iptv(name = "目标", channelName = "target")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "分组1", iptvList = IptvList(listOf(Iptv(name = "A")))),
                IptvGroup(name = "分组2", iptvList = IptvList(listOf(Iptv(name = "B"), target))),
                IptvGroup(name = "分组3", iptvList = IptvList(listOf(target))),
            )
        )

        assertEquals(0, groupList.iptvIdx(Iptv(name = "A")))
        assertEquals(2, groupList.iptvIdx(target))
        assertEquals(1, groupList.iptvGroupIdx(target))
        assertEquals(-1, groupList.iptvIdx(Iptv(name = "不存在")))
        assertEquals(-1, IptvGroupList().iptvIdx(target))
        // 下标与按下标取台互为逆运算
        assertEquals(target, groupList.iptvAt(groupList.iptvIdx(target)))
    }

    @Test
    fun `search matches name ignoring case dashes and spaces`() {
        val cctv1 = Iptv(name = "CCTV-1 综合", channelName = "CCTV-1 综合", tvgId = "cctv1")
        val cctv2 = Iptv(name = "CCTV-2 财经", channelName = "CCTV-2 财经", tvgId = "cctv2")
        val hnws = Iptv(name = "湖南卫视", channelName = "湖南卫视", tvgId = "hnws")
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "全部", iptvList = IptvList(listOf(cctv1, cctv2, hnws))))
        )

        // “cctv1” 命中 “CCTV-1 综合”，“cctv2” 不串台
        assertEquals(listOf(cctv1), groupList.searchIptv("cctv1").value)
        assertEquals(listOf(cctv2), groupList.searchIptv("CCTV-2").value)
        // 中文子串命中，空白查询词给空列表、不返回全量频道
        assertEquals(listOf(hnws), groupList.searchIptv("湖南").value)
        assertEquals(listOf(cctv1, cctv2), groupList.searchIptv("CCTV").value)
        assertEquals(0, groupList.searchIptv("").size)
        assertEquals(0, groupList.searchIptv("   ").size)
        assertEquals(0, groupList.searchIptv("不存在的台").size)
    }

    @Test
    fun `search also matches tvg-id and dedups identical channels`() {
        // tvg-name 是纯 id 的源：名字里没有 cctv 字样时靠 tvg-id 命中；
        // 同一台在两个分组里出现时搜索结果只留一个
        val byId = Iptv(name = "综合", channelName = "综合", tvgId = "cctv1")
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "分组1", iptvList = IptvList(listOf(byId))),
                IptvGroup(name = "分组2", iptvList = IptvList(listOf(byId))),
            )
        )

        assertEquals(listOf(byId), groupList.searchIptv("cctv1").value)
    }

    @Test
    fun `recent list follows recent order and skips missing channels`() {
        val cctv1 = Iptv(name = "CCTV-1", channelName = "cctv1")
        val cctv2 = Iptv(name = "CCTV-2", channelName = "cctv2")
        val hnws = Iptv(name = "湖南卫视", channelName = "hnws")
        val groupList = IptvGroupList(
            listOf(IptvGroup(name = "全部", iptvList = IptvList(listOf(cctv1, cctv2, hnws))))
        )

        // 最近名单最前面的是最后看过的；不在源里的名字直接跳过、不占位
        assertEquals(
            listOf(hnws, cctv1),
            groupList.recentIptvList(listOf("hnws", "不存在的台", "cctv1")).value
        )
        assertEquals(0, groupList.recentIptvList(emptyList()).size)
    }

    @Test
    fun `recent list keeps only first occurrence of duplicated channel`() {
        val first = Iptv(name = "CCTV-1", channelName = "cctv1", urlList = listOf("http://a.com/1.m3u8"))
        val second = Iptv(name = "CCTV-1", channelName = "cctv1", urlList = listOf("http://b.com/1.m3u8"))
        val groupList = IptvGroupList(
            listOf(
                IptvGroup(name = "央视", iptvList = IptvList(listOf(first))),
                IptvGroup(name = "全部", iptvList = IptvList(listOf(second))),
            )
        )

        assertEquals(listOf(first), groupList.recentIptvList(listOf("cctv1")).value)
    }
}
