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
}
