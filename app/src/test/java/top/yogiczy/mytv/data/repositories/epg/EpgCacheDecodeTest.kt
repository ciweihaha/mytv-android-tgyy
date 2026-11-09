package top.yogiczy.mytv.data.repositories.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgCacheDecodeTest {
    @Test
    fun `decodes valid cache`() {
        val decoded = decodeEpgListOrNull(
            """[{"channel":"CCTV-1","programmes":[{"startAt":1000,"endAt":2000,"title":"新闻"}]}]"""
        )
        assertEquals(1, decoded?.size)
        assertEquals("CCTV-1", decoded?.first()?.channel)
        assertEquals("新闻", decoded?.first()?.programmes?.first()?.title)
    }

    @Test
    fun `decodes empty list`() {
        assertEquals(0, decodeEpgListOrNull("[]")?.size)
    }

    @Test
    fun `returns null for blank`() {
        assertNull(decodeEpgListOrNull(null))
        assertNull(decodeEpgListOrNull(""))
        assertNull(decodeEpgListOrNull("   "))
    }

    @Test
    fun `returns null for truncated json`() {
        assertNull(decodeEpgListOrNull("""[{"channel":"CCTV-1""""))
        assertNull(decodeEpgListOrNull("not json"))
    }

    @Test
    fun `returns null for wrong shape`() {
        assertNull(decodeEpgListOrNull("{}"))
        assertNull(decodeEpgListOrNull("""[{"channel":123}]"""))
    }
}
