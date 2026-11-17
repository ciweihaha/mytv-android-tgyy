package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentChannelsTest {
    @Test
    fun `record puts latest first and dedups`() {
        assertEquals(listOf("cctv2"), recordRecentChannel(emptyList(), "cctv2"))
        assertEquals(
            listOf("cctv2", "cctv1"),
            recordRecentChannel(listOf("cctv1"), "cctv2")
        )
        // 再看一次 cctv1：提到最前面，旧记录去掉、只留一份
        assertEquals(
            listOf("cctv1", "cctv2"),
            recordRecentChannel(listOf("cctv2", "cctv1"), "cctv1")
        )
    }

    @Test
    fun `record caps at max and keeps most recent`() {
        val recent = (1..10).map { "cctv$it" }
        assertEquals(
            listOf("cctv11", "cctv1", "cctv2", "cctv3", "cctv4", "cctv5", "cctv6", "cctv7", "cctv8", "cctv9"),
            recordRecentChannel(recent, "cctv11")
        )
        assertEquals(emptyList<String>(), recordRecentChannel(listOf("cctv1"), "cctv2", max = 0))
    }

    @Test
    fun `record ignores blank channel name`() {
        // 空源占位频道（channelName 是空串）不该进最近列表；空白名
        // 不记，但已有名单原样保留（只截到上限）
        assertEquals(emptyList<String>(), recordRecentChannel(emptyList(), ""))
        assertEquals(listOf("cctv1"), recordRecentChannel(listOf("cctv1"), "   "))
    }

    @Test
    fun `encode and decode round-trip keeps order`() {
        val recent = listOf("hnws", "cctv1", "cctv2")
        assertEquals(recent, decodeRecentChannels(encodeRecentChannels(recent)))
        assertEquals(emptyList<String>(), decodeRecentChannels(""))
        assertEquals(emptyList<String>(), decodeRecentChannels(null))
        // 编码时空白名丢掉、解码时空行丢掉
        assertEquals(listOf("cctv1"), decodeRecentChannels(encodeRecentChannels(listOf("", "cctv1", " "))))
        assertEquals(listOf("cctv1", "cctv2"), decodeRecentChannels("cctv1\n\ncctv2\n"))
    }
}
