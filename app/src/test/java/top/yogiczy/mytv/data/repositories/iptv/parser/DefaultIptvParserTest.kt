package top.yogiczy.mytv.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultIptvParserTest {
    private val parser = DefaultIptvParser()

    @Test
    fun `isSupport always returns true`() {
        assertTrue(parser.isSupport("https://example.com/anything", "whatever"))
        assertTrue(parser.isSupport("", ""))
    }

    @Test
    fun `parse returns a hint group with m3u and tvbox entries`() = runBlocking {
        val result = parser.parse("not a supported format")

        assertEquals(1, result.size)
        assertTrue(result[0].name.contains("m3u"))
        assertEquals(2, result[0].iptvList.size)
        assertEquals("m3u", result[0].iptvList[0].name)
        assertEquals("tvbox", result[0].iptvList[1].name)
        assertTrue(result[0].iptvList[0].urlList.isEmpty())
    }

    @Test
    fun `parse returns exact hint text and channel names`() = runBlocking {
        // 占位文案与频道名是用户看得见的：上一档只断言了分组名含 m3u、
        // 频道名与 channelName、第二条的空地址一次也没钉死，改错了
        // 格式不对的源就只剩一条看不懂的提示
        val result = parser.parse("not a supported format")

        assertEquals(
            "不支持当前直播源链接格式，请切换其他直播源链接；支持的直播源链接格式：m3u、tvbox",
            result[0].name,
        )
        assertEquals("m3u", result[0].iptvList[0].channelName)
        assertEquals("tvbox", result[0].iptvList[1].channelName)
        assertTrue(result[0].iptvList[0].urlList.isEmpty())
        assertTrue(result[0].iptvList[1].urlList.isEmpty())
    }
}
