package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class HttpServerLanAddressTest {
    private fun candidate(ip: String, pointToPoint: Boolean = false) =
        LanAddressCandidate(InetAddress.getByName(ip), pointToPoint)

    @Test
    fun `prefers site-local over public`() {
        val picked = pickLanAddress(
            listOf(
                candidate("8.8.8.8"),
                candidate("192.168.1.5"),
            )
        )
        assertEquals("192.168.1.5", picked?.hostAddress)
    }

    @Test
    fun `falls back to first non-loopback when no site-local`() {
        val picked = pickLanAddress(
            listOf(
                candidate("8.8.8.8"),
                candidate("1.1.1.1"),
            )
        )
        assertEquals("8.8.8.8", picked?.hostAddress)
    }

    @Test
    fun `skips loopback and ipv6`() {
        val picked = pickLanAddress(
            listOf(
                candidate("127.0.0.1"),
                candidate("::1"),
                candidate("10.0.0.2"),
            )
        )
        assertEquals("10.0.0.2", picked?.hostAddress)
    }

    @Test
    fun `returns null when nothing usable`() {
        assertNull(pickLanAddress(listOf(candidate("127.0.0.1"))))
        assertNull(pickLanAddress(emptyList()))
    }

    @Test
    fun `skips point-to-point site-local in favor of real site-local`() {
        val picked = pickLanAddress(
            listOf(
                candidate("10.8.0.2", pointToPoint = true),
                candidate("192.168.1.5"),
            )
        )
        assertEquals("192.168.1.5", picked?.hostAddress)
    }

    @Test
    fun `skips point-to-point site-local in favor of real public`() {
        val picked = pickLanAddress(
            listOf(
                candidate("10.8.0.2", pointToPoint = true),
                candidate("8.8.8.8"),
            )
        )
        assertEquals("8.8.8.8", picked?.hostAddress)
    }

    @Test
    fun `falls back to point-to-point when nothing else`() {
        val picked = pickLanAddress(
            listOf(
                candidate("10.8.0.2", pointToPoint = true),
            )
        )
        assertEquals("10.8.0.2", picked?.hostAddress)
    }

    @Test
    fun `prefers point-to-point site-local over point-to-point public`() {
        // 只有 VPN 接口时也要先挑内网段：第三档（点对点内网）优先于
        // 第四档（点对点其他），不能按列表顺序拿第一个
        val picked = pickLanAddress(
            listOf(
                candidate("8.8.8.8", pointToPoint = true),
                candidate("10.8.0.2", pointToPoint = true),
            )
        )
        assertEquals("10.8.0.2", picked?.hostAddress)
    }
}
