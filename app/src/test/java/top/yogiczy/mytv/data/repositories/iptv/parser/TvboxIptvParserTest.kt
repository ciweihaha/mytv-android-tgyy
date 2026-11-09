package top.yogiczy.mytv.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvboxIptvParserTest {
    private val parser = TvboxIptvParser()

    @Test
    fun `isSupport accepts tvbox format only`() {
        assertTrue(parser.isSupport("https://example.com/tv.txt", "央视,#genre#\nCCTV-1,http://example.com/1"))
        assertFalse(parser.isSupport("https://example.com/tv.txt", "#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/tv.txt", ""))
    }

    @Test
    fun `parses channels grouped by genre`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1.m3u8
            CCTV-5,http://example.com/cctv5.m3u8
            卫视,#genre#
            湖南卫视,http://example.com/hunan.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("卫视", result[1].name)
        assertEquals("湖南卫视", result[1].iptvList[0].name)
    }

    @Test
    fun `multiple urls separated by hash merge into one entry`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1-a.m3u8#http://example.com/cctv1-b.m3u8
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
    fun `chinese comma is treated as separator`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1，http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `channels before any genre fall back to 其他 and blank lines are skipped`() = runBlocking {
        val data = """
            CCTV-1,http://example.com/cctv1.m3u8

            # a comment line
            央视,#genre#
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("央视", result[1].name)
    }
}
