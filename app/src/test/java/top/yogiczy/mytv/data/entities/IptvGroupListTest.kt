package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.favoriteIptvList

class IptvGroupListTest {
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
}
