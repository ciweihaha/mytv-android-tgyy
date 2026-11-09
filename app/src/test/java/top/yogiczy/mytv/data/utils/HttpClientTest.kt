package top.yogiczy.mytv.data.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpClientTest {
    @Test
    fun `shared client has bounded timeouts`() {
        assertEquals(15_000, sharedHttpClient.connectTimeoutMillis)
        assertEquals(30_000, sharedHttpClient.readTimeoutMillis)
        assertEquals(15_000, sharedHttpClient.writeTimeoutMillis)
        assertEquals(120_000, sharedHttpClient.callTimeoutMillis)
    }
}
