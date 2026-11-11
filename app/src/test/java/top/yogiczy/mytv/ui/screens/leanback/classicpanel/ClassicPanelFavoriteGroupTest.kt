package top.yogiczy.mytv.ui.screens.leanback.classicpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.screens.leanback.classicpanel.components.classicPanelGroupKey

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
}
