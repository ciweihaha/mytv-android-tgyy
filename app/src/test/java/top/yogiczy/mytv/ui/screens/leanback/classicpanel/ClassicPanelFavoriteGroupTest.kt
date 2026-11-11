package top.yogiczy.mytv.ui.screens.leanback.classicpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.screens.leanback.classicpanel.components.classicPanelGroupIndex
import top.yogiczy.mytv.ui.screens.leanback.classicpanel.components.classicPanelGroupKey
import top.yogiczy.mytv.ui.screens.leanback.classicpanel.components.isSameClassicPanelGroup

/**
 * 经典面板“我的收藏”假分组：第 178 轮把两处按值比的地方改成
 * 按引用/下标认——源里也可能有个同名分组，空的同名分组按值算
 * 和假分组一模一样，分组列表的 key 也会撞车。这里验收：假分组
 * 本身认得出来，同名不同对象认不出来，同名不同下标的 key 不一样。
 */
class ClassicPanelFavoriteGroupTest {
    @Test
    fun `favorite group itself is recognized`() {
        assertTrue(isFavoriteIptvGroup(LeanbackClassicPanelScreenFavoriteIptvGroup))
    }

    @Test
    fun `empty same-named source group is not the favorite group`() {
        // 名字相同、频道列表都是空，按值和假分组一模一样——
        // 但它是解析出来的另一个对象，不能当成收藏分组
        assertFalse(isFavoriteIptvGroup(IptvGroup(name = "我的收藏")))
    }

    @Test
    fun `non-empty same-named source group is not the favorite group`() {
        assertFalse(
            isFavoriteIptvGroup(
                IptvGroup(
                    name = "我的收藏",
                    iptvList = IptvList(listOf(Iptv.EXAMPLE)),
                )
            )
        )
    }

    @Test
    fun `ordinary group is not the favorite group`() {
        assertFalse(isFavoriteIptvGroup(IptvGroup(name = "央视频道")))
    }

    @Test
    fun `same name at different indices gets different keys`() {
        assertNotEquals(
            classicPanelGroupKey(0, "我的收藏"),
            classicPanelGroupKey(1, "我的收藏"),
        )
    }

    @Test
    fun `same index and name gets the same key`() {
        assertEquals(
            classicPanelGroupKey(0, "我的收藏"),
            classicPanelGroupKey(0, "我的收藏"),
        )
    }

    // 第 179 轮：分组列表里“选中”和“初始焦点”原来按值比，源里一个空的
    // 同名“我的收藏”分组按值和假分组一模一样，点中它那一行时两行会
    // 同时显示选中；初始滚动位置用的 indexOf 也按值比，会错停在第 0 位
    // 的假分组上。现在三处都按引用认。

    @Test
    fun `same object is the same group`() {
        assertTrue(isSameClassicPanelGroup(LeanbackClassicPanelScreenFavoriteIptvGroup, LeanbackClassicPanelScreenFavoriteIptvGroup))
    }

    @Test
    fun `equal-valued different object is not the same group`() {
        // 名字相同、频道列表都是空，按值和假分组一模一样——
        // 但它是另一个对象，选中它时假分组那一行不能跟着亮
        assertFalse(
            isSameClassicPanelGroup(
                IptvGroup(name = "我的收藏"),
                LeanbackClassicPanelScreenFavoriteIptvGroup,
            )
        )
    }

    @Test
    fun `different group is not the same group`() {
        assertFalse(
            isSameClassicPanelGroup(
                IptvGroup(name = "央视频道"),
                LeanbackClassicPanelScreenFavoriteIptvGroup,
            )
        )
    }

    @Test
    fun `index of same-named source group is its own position not the favorite group`() {
        // 假分组在第 0 位，源里空的同名分组在第 1 位：按值找会先撞上
        // 第 0 位的假分组回 0，按引用找才回它自己的位置 1
        val sourceGroup = IptvGroup(name = "我的收藏")
        val list = IptvGroupList(
            listOf(LeanbackClassicPanelScreenFavoriteIptvGroup, sourceGroup)
        )
        assertEquals(1, classicPanelGroupIndex(list, sourceGroup))
        assertEquals(0, classicPanelGroupIndex(list, LeanbackClassicPanelScreenFavoriteIptvGroup))
    }

    @Test
    fun `index of group not in list is minus one`() {
        val list = IptvGroupList(
            listOf(LeanbackClassicPanelScreenFavoriteIptvGroup)
        )
        assertEquals(-1, classicPanelGroupIndex(list, IptvGroup(name = "我的收藏")))
    }
}
