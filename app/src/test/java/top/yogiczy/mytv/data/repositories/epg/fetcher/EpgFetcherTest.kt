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
        assertFalse(gzFetcher.isSupport("https://example.com/epg.xml"))
        assertFalse(gzFetcher.isSupport("https://example.com/epg"))
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
}
