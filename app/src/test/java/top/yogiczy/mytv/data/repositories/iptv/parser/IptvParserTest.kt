package top.yogiczy.mytv.data.repositories.iptv.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class IptvParserTest {

    @Test
    fun `dispatch picks m3u tvbox and default parsers in order`() {
        // 分发只看 instances 顺序：Default 的 isSupport 永远 true，
        // 被挪到前面时各解析器自己的 isSupport 用例照样通过、分发却全变
        // 成提示页。这里直接断言 IptvRepository 那行
        // `instances.first { it.isSupport(url, data) }` 挑到的是谁
        //（EPG 那边 EpgFetcherTest 有同款分发用例，直播源这边一直没有）
        assertTrue(
            IptvParser.instances.first {
                it.isSupport("https://example.com/live.m3u", "#EXTM3U\n#EXTINF:-1,CCTV-1")
            } is M3uIptvParser
        )
        assertTrue(
            IptvParser.instances.first {
                it.isSupport("https://example.com/live.txt", "央视,#genre#\nCCTV-1,http://example.com/1")
            } is TvboxIptvParser
        )
        assertTrue(
            IptvParser.instances.first {
                it.isSupport("https://example.com/anything", "not a supported format")
            } is DefaultIptvParser
        )
    }
}
