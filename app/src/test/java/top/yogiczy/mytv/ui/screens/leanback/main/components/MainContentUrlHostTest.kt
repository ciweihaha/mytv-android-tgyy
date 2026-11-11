package top.yogiczy.mytv.ui.screens.leanback.main.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MainContentUrlHostTest {
    @Test
    fun `带协议的地址取域名或域名加端口`() {
        assertEquals("example.com", getUrlHost("http://example.com/live.m3u8"))
        assertEquals("example.com:8080", getUrlHost("https://example.com:8080/a/b"))
        assertEquals("example.com", getUrlHost("http://example.com"))
    }

    @Test
    fun `没写协议的地址取第一段不再全记成空串`() {
        // tvbox 源里没写协议的地址（如局域网 192.168.1.1:8080/live）：
        // 原来全都记成空串，一个能播就把所有没写协议的地址都当成能播
        assertEquals("192.168.1.1:8080", getUrlHost("192.168.1.1:8080/live"))
        assertEquals("192.168.1.1", getUrlHost("192.168.1.1/live"))
        // 不同地址要能分开记
        assertEquals("192.168.1.2:8080", getUrlHost("192.168.1.2:8080/live"))
    }

    @Test
    fun `空地址还是空串`() {
        assertEquals("", getUrlHost(""))
    }
}
