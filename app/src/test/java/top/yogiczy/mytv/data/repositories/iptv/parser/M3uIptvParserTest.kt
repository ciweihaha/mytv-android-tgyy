package top.yogiczy.mytv.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uIptvParserTest {
    private val parser = M3uIptvParser()

    @Test
    fun `isSupport accepts EXT-M3U playlists only`() {
        assertTrue(parser.isSupport("https://example.com/list.m3u", "#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", "央视,#genre#\nCCTV-1,http://example.com/1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", ""))
    }

    @Test
    fun `parses channels grouped by group-title`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("卫视", result[1].name)
        assertEquals("湖南卫视", result[1].iptvList[0].name)
    }

    @Test
    fun `missing group-title falls back to 其他`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1,无分组频道
            http://example.com/nogroup.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("无分组频道", result[0].iptvList[0].name)
    }

    @Test
    fun `duplicate channel names merge into one entry with multiple urls`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1-b.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals(
            listOf("http://example.com/cctv1-a.m3u8", "http://example.com/cctv1-b.m3u8"),
            result[0].iptvList[0].urlList,
        )
    }

    @Test
    fun `handles CRLF line endings`() = runBlocking {
        val data = "#EXTM3U\r\n#EXTINF:-1 group-title=\"央视\",CCTV-1\r\nhttp://example.com/cctv1.m3u8\r\n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `isSupport accepts BOM-prefixed and leading-blank-line playlists`() {
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\uFEFF#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\n\n#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\uFEFF\n#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", "\uFEFF央视,#genre#\nCCTV-1,http://example.com/1"))
    }

    @Test
    fun `parses BOM-prefixed playlist normally`() = runBlocking {
        val data = "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\nhttp://example.com/cctv1.m3u8\n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `trailing EXTINF without url line does not crash and is skipped`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-5
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }
}
