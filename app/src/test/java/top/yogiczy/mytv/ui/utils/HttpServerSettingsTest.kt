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

    @Test
    fun `force refresh when cachedAt is zero`() {
        assertEquals(true, isForceRefreshCache(0L))
    }

    @Test
    fun `no force refresh when cachedAt is missing`() {
        // 字段缺失时服务端用 -1 兜底：老页面、只改 UA 的推送不强制刷新
        assertEquals(false, isForceRefreshCache(-1L))
    }

    @Test
    fun `no force refresh when cachedAt is a real timestamp`() {
        assertEquals(false, isForceRefreshCache(1_700_000_000_000L))
    }
}
