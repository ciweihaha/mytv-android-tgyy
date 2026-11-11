package top.yogiczy.mytv.data.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpClientTest {
    @Test
    fun `shared client has bounded timeouts`() {
        assertEquals(15_000, sharedHttpClient.connectTimeoutMillis)
        assertEquals(30_000, sharedHttpClient.readTimeoutMillis)
        assertEquals(15_000, sharedHttpClient.writeTimeoutMillis)
        assertEquals(120_000, sharedHttpClient.callTimeoutMillis)
    }

    @Test
    fun `buildGetRequest builds a request for a valid url`() {
        val request = buildGetRequest("https://live.ciweihaha.win/live.m3u?token=a,b", "直播源地址")

        assertEquals("https://live.ciweihaha.win/live.m3u?token=a,b", request.url.toString())
    }

    @Test
    fun `buildGetRequest rejects a url without scheme with a chinese message`() {
        try {
            buildGetRequest("live.ciweihaha.win/live.m3u", "直播源地址")
            fail("expected an exception for a url without scheme")
        } catch (ex: Exception) {
            assertTrue(ex.message?.contains("直播源地址格式不正确") == true)
            assertTrue(ex.message?.contains("live.ciweihaha.win/live.m3u") == true)
        }
    }

    @Test
    fun `buildGetRequest rejects blank and space-containing urls`() {
        for (bad in listOf("", "   ", "http://exa mple.com/live.m3u")) {
            try {
                buildGetRequest(bad, "节目单地址")
                fail("expected an exception for [$bad]")
            } catch (ex: Exception) {
                assertTrue(ex.message?.contains("节目单地址格式不正确") == true)
            }
        }
    }

    @Test
    fun `buildGetRequest rejects a non-http scheme`() {
        // 拉源只走 HTTP(S)：rtmp 这类播放地址不能当源地址拉
        try {
            buildGetRequest("rtmp://example.com/live", "直播源地址")
            fail("expected an exception for a non-http scheme")
        } catch (ex: Exception) {
            assertTrue(ex.message?.contains("直播源地址格式不正确") == true)
        }
    }
}
