package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgFetcherTest {
    private val xmlFetcher = XmlEpgFetcher()
    private val gzFetcher = XmlGzEpgFetcher()
    private val defaultFetcher = DefaultEpgFetcher()

    private fun responseWithBody(body: ResponseBody): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com/epg").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun `xml fetcher matches urls with query string, fragment and uppercase extension`() {
        assertTrue(xmlFetcher.isSupport("https://example.com/epg.xml"))
        assertTrue(xmlFetcher.isSupport("https://example.com/epg.xml?token=abc"))
        assertTrue(xmlFetcher.isSupport("https://example.com/epg.xml#frag"))
        assertTrue(xmlFetcher.isSupport("https://example.com/EPG.XML"))
        assertFalse(xmlFetcher.isSupport("https://example.com/epg.xml.gz"))
        assertFalse(xmlFetcher.isSupport("https://example.com/epg"))
    }

    @Test
    fun `gz fetcher matches urls with query string`() {
        assertTrue(gzFetcher.isSupport("https://example.com/epg.xml.gz"))
        assertTrue(gzFetcher.isSupport("https://example.com/epg.gz?token=abc"))
        // 大写后缀也认：xml 那边 `EPG.XML` 已钉死大小写不敏感，gz
        // 这边一次也没断言过——matchExtension 里的 ignoreCase 被人
        // 顺手拿掉时，xml 那档会红、gz 这档却悄悄失效，真实源里
        // .XML.GZ / .GZ 的订阅地址就分发不到 gz 解析器了
        assertTrue(gzFetcher.isSupport("https://example.com/epg.XML.GZ"))
        assertTrue(gzFetcher.isSupport("https://example.com/epg.GZ"))
        assertFalse(gzFetcher.isSupport("https://example.com/epg.xml"))
        assertFalse(gzFetcher.isSupport("https://example.com/epg"))
    }

    @Test
    fun `extension only in query or fragment does not match`() {
        // 后缀只出现在问号/井号后面时不算命中：`epg?file=x.xml` 的 .xml
        // 在参数里，路径本身没后缀，该走 Default 解析器嗅探内容，而
        // 不是被 xml 解析器认领；只断言 `epg.xml?token=abc` 那档盖不
        // 住这条——去掉 query/fragment 再比的逻辑被删时，真地址那档
        // 照样通过，只有这档会失败
        assertFalse(xmlFetcher.isSupport("https://example.com/epg?file=x.xml"))
        assertFalse(xmlFetcher.isSupport("https://example.com/epg#x.xml"))
        assertFalse(gzFetcher.isSupport("https://example.com/epg?file=x.xml.gz"))
        assertFalse(gzFetcher.isSupport("https://example.com/epg#x.xml.gz"))
    }

    @Test
    fun `query-only suffix dispatches to default fetcher`() {
        // 上一档只断言了各解析器认不认领，没断言分发结果：instances
        // 顺序被人调换（比如 Default 被挪到前面）时，上面四档照样
        // 通过，分发却变了。这里直接断言 `EpgRepository` 那行
        // `instances.first { it.isSupport(url) }` 挑到的是 Default
        assertTrue(
            EpgFetcher.instances.first {
                it.isSupport("https://example.com/epg?file=x.xml")
            } is DefaultEpgFetcher
        )
        assertTrue(
            EpgFetcher.instances.first {
                it.isSupport("https://example.com/epg?file=x.xml.gz")
            } is DefaultEpgFetcher
        )
    }

    @Test
    fun `dispatch picks xml and gz fetchers before default`() {
        // 上一档只断了 query-only 后缀走 Default，没断正向分发：
        // Default 的 isSupport 永远 true，被挪到前面时 .xml / .xml.gz
        // 全变成 Default 嗅探，上一档照样通过、一个用例都不会红。
        // 这里直接断言 `instances.first { it.isSupport(url) }`
        // 挑到的是谁（直播源那边 IptvParserTest 有同款三分发用例）
        assertTrue(
            EpgFetcher.instances.first {
                it.isSupport("https://example.com/epg.xml")
            } is XmlEpgFetcher
        )
        assertTrue(
            EpgFetcher.instances.first {
                it.isSupport("https://example.com/epg.xml.gz")
            } is XmlGzEpgFetcher
        )
    }

    @Test
    fun `default fetcher returns plain xml body as-is`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = defaultFetcher.fetch(responseWithBody(xml.toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `default fetcher gunzips gzip body for extension-less urls`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = defaultFetcher.fetch(responseWithBody(gzip(xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `gz fetcher decompresses`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = gzFetcher.fetch(responseWithBody(gzip(xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `xml fetcher gunzips gzip body served under xml url`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = xmlFetcher.fetch(responseWithBody(gzip(xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `decodeBody does not gunzip when only first magic byte matches`() {
        val body = byteArrayOf(0x1F, 0x41, 0x42).toResponseBody()

        val result = defaultFetcher.fetch(responseWithBody(body))

        assertEquals("\u001FAB", result)
    }

    @Test
    fun `empty body returns empty string`() {
        val result = xmlFetcher.fetch(responseWithBody("".toResponseBody()))

        assertEquals("", result)
    }

    @Test
    fun `null body returns empty string`() {
        // 响应体为 null 时直接回空串，不抛空指针：真实 HTTP 响应
        // 一般总有 body，但接口上 body 可空（204 之类），这行守卫
        // 没单测时重构容易顺手删掉——删了这里就空指针
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/epg").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .build()

        val result = defaultFetcher.fetch(response)

        assertEquals("", result)
    }

    @Test
    fun `leading BOM is stripped from plain xml body`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = defaultFetcher.fetch(responseWithBody(("\uFEFF" + xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `leading BOM is stripped from gzipped body`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = gzFetcher.fetch(responseWithBody(gzip("\uFEFF" + xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `double leading BOM is stripped from plain xml body`() {
        // 反复转存、拼接过的源开头可能带两个 BOM：removePrefix 只去
        // 一个，剩一个 BOM 时 KXml 在 "PI must not start with xml" 上
        // 抛错、整个节目单加载失败；开头有几个去几个
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = defaultFetcher.fetch(responseWithBody(("\uFEFF\uFEFF" + xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `double leading BOM is stripped from gzipped body`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"c1\" /></tv>"

        val result = gzFetcher.fetch(responseWithBody(gzip("\uFEFF\uFEFF" + xml).toResponseBody()))

        assertEquals(xml, result)
    }

    @Test
    fun `BOM in the middle is left alone`() {
        val xml = "<tv><channel id=\"c1\" />\uFEFF</tv>"

        val result = defaultFetcher.fetch(responseWithBody(xml.toResponseBody()))

        assertEquals(xml, result)
    }
}
