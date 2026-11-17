package top.yogiczy.mytv.data.utils

import org.junit.Assert.assertEquals
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
            // 2358 轮收紧成全句：原来两行 contains（“直播源地址格式不正确”
            // ＋原地址）合起来已接近全句，但“，请检查地址：”这半句丢了也
            // 不会红；模板是同一条（HttpClient.kt:51），全句相等断得住。
            assertEquals(
                "直播源地址格式不正确，请检查地址：live.ciweihaha.win/live.m3u",
                ex.message,
            )
        }
    }

    @Test
    fun `buildGetRequest throws InvalidSourceUrlException for a bad url`() {
        // 格式错单独成类：调用方靠类型认出来、跳过重试，立刻报错
        try {
            buildGetRequest("live.ciweihaha.win/live.m3u", "直播源地址")
            fail("expected InvalidSourceUrlException for a url without scheme")
        } catch (ex: InvalidSourceUrlException) {
            // 与上一档同款收紧成全句（2358 轮）。
            assertEquals(
                "直播源地址格式不正确，请检查地址：live.ciweihaha.win/live.m3u",
                ex.message,
            )
        }
    }

    @Test
    fun `buildGetRequest rejects blank and space-containing urls`() {
        // 三档输入、同一条模板：空串与纯空格也要原样带进文案——用户看到
        // 自己输的东西才能去检查地址；若将来谁把地址先 trim 再拼进文案，
        // 纯空格那档的尾部空格没了会先红，逼着把语义说清楚（2358 轮收紧）。
        val cases = listOf(
            "" to "节目单地址格式不正确，请检查地址：",
            "   " to "节目单地址格式不正确，请检查地址：   ",
            "http://exa mple.com/live.m3u" to "节目单地址格式不正确，请检查地址：http://exa mple.com/live.m3u",
        )
        for ((bad, expected) in cases) {
            try {
                buildGetRequest(bad, "节目单地址")
                fail("expected an exception for [$bad]")
            } catch (ex: Exception) {
                assertEquals(expected, ex.message)
            }
        }
    }

    @Test
    fun `buildGetRequest trims surrounding whitespace instead of rejecting`() {
        // 2157 轮留下的尾巴：首尾空白那档一直没定论——OkHttp 5.5.1
        // 源码（HttpUrl.Builder.parse）先跳过首尾的 \t \n \r 空格再解析，
        // 所以手输地址时不小心粘上的首尾空格不会报错，请求按去掉空格
        // 后的地址发；中间带空格（如 http://exa mple.com）仍是格式错，
        // 上一档已钉死
        val request = buildGetRequest("  https://live.ciweihaha.win/live.m3u  ", "直播源地址")
        assertEquals("https://live.ciweihaha.win/live.m3u", request.url.toString())

        val tabbed = buildGetRequest("\thttps://live.ciweihaha.win/live.m3u\n", "节目单地址")
        assertEquals("https://live.ciweihaha.win/live.m3u", tabbed.url.toString())
    }

    @Test
    fun `buildGetRequest rejects a non-http scheme`() {
        // 拉源只走 HTTP(S)：rtmp 这类播放地址不能当源地址拉
        try {
            buildGetRequest("rtmp://example.com/live", "直播源地址")
            fail("expected an exception for a non-http scheme")
        } catch (ex: Exception) {
            // 与无协议那档同款收紧成全句（2358 轮）。
            assertEquals(
                "直播源地址格式不正确，请检查地址：rtmp://example.com/live",
                ex.message,
            )
        }
    }
}
