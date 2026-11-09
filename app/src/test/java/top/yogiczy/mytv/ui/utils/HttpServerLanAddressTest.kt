package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class HttpServerLanAddressTest {
    @Test
    fun `prefers site-local over public`() {
        val picked = pickLanAddress(
            listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("192.168.1.5"),
            )
        )
        assertEquals("192.168.1.5", picked?.hostAddress)
    }

    @Test
    fun `falls back to first non-loopback when no site-local`() {
        val picked = pickLanAddress(
            listOf(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.1.1.1"),
            )
        )
        assertEquals("8.8.8.8", picked?.hostAddress)
    }

    @Test
    fun `skips loopback and ipv6`() {
        val picked = pickLanAddress(
            listOf(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("::1"),
                InetAddress.getByName("10.0.0.2"),
            )
        )
        assertEquals("10.0.0.2", picked?.hostAddress)
    }

    @Test
    fun `returns null when nothing usable`() {
        assertNull(pickLanAddress(listOf(InetAddress.getByName("127.0.0.1"))))
        assertNull(pickLanAddress(emptyList()))
    }
}
