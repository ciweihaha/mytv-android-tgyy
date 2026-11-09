package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpServerSettingsTest {
    @Test
    fun `blank value keeps current`() {
        assertEquals(
            "http://example.com/a.m3u",
            keepCurrentIfBlank("", "http://example.com/a.m3u")
        )
    }

    @Test
    fun `whitespace-only value keeps current`() {
        assertEquals(
            "http://example.com/a.m3u",
            keepCurrentIfBlank("   ", "http://example.com/a.m3u")
        )
    }

    @Test
    fun `non-blank value replaces current`() {
        assertEquals(
            "http://example.com/b.m3u",
            keepCurrentIfBlank("http://example.com/b.m3u", "http://example.com/a.m3u")
        )
    }
}
