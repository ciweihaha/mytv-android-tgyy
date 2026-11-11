package top.yogiczy.mytv.ui.screens.leanback.panel

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.screens.leanback.panel.components.epgNeighbor

/**
 * 节目单弹窗左右键切频道：第 169 轮把普通面板频道列表和收藏列表里
 * 一模一样的“找下标、加减 1、夹住不越界”抽成了共用的 epgNeighbor，
 * 只差左（-1）还是右（+1）。这里验收抽出来的那份算出来和原来
 * 写死的一模一样：中间正常前后切，到头到尾停在原地，空列表不动，
 * 当前频道不在列表里时左右都回到第一个。
 */
class PanelIptvEpgNavigationTest {
    private val first = Iptv(name = "CCTV-1", channelName = "cctv1")
    private val second = Iptv(name = "CCTV-2", channelName = "cctv2")
    private val third = Iptv(name = "CCTV-3", channelName = "cctv3")
    private val list = IptvList(listOf(first, second, third))

    @Test
    fun `middle channel moves to previous and next`() {
        assertEquals(first, list.epgNeighbor(second, -1))
        assertEquals(third, list.epgNeighbor(second, +1))
    }

    @Test
    fun `first channel stays on left and last stays on right`() {
        assertEquals(first, list.epgNeighbor(first, -1))
        assertEquals(third, list.epgNeighbor(third, +1))
    }

    @Test
    fun `empty list keeps current channel`() {
        val current = Iptv(name = "CCTV-1", channelName = "cctv1")
        assertEquals(current, IptvList().epgNeighbor(current, -1))
        assertEquals(current, IptvList().epgNeighbor(current, +1))
    }

    @Test
    fun `channel not in list goes back to first either way`() {
        val missing = Iptv(name = "CCTV-9", channelName = "cctv9")
        assertEquals(first, list.epgNeighbor(missing, -1))
        assertEquals(first, list.epgNeighbor(missing, +1))
    }

    @Test
    fun `single channel list stays put`() {
        val single = IptvList(listOf(first))
        assertEquals(first, single.epgNeighbor(first, -1))
        assertEquals(first, single.epgNeighbor(first, +1))
    }
}
