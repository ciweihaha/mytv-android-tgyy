package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.PlaybackException as Media3PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3TransientIoRetryTest {
    @Test
    fun `2004 是瞬时网络错`() {
        assertEquals(2004, Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertTrue(isTransientIoError(Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
    }

    @Test
    fun `2001 和 2002 是瞬时网络错`() {
        assertEquals(2001, Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(2002, Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertTrue(isTransientIoError(Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isTransientIoError(Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test
    fun `其他错误码不是瞬时网络错`() {
        assertFalse(isTransientIoError(Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertFalse(isTransientIoError(Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertFalse(isTransientIoError(Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }

    @Test
    fun `2004 连报五次都重试、第六次上报`() {
        val errorCode = Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        var retries = 0
        repeat(5) {
            assertTrue(shouldRetryTransientIoError(errorCode, retries))
            retries++
        }
        assertFalse(shouldRetryTransientIoError(errorCode, retries))
    }

    @Test
    fun `非瞬时网络错一次也不重试`() {
        assertFalse(
            shouldRetryTransientIoError(
                Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                0,
            ),
        )
    }

    @Test
    fun `第 1 次重试等 1 秒、第 2 次 2 秒、第 3 次 3 秒、第 4 次 5 秒、第 5 次 8 秒`() {
        assertEquals(1000L, retryDelayMs(1))
        assertEquals(2000L, retryDelayMs(2))
        assertEquals(3000L, retryDelayMs(3))
        assertEquals(5000L, retryDelayMs(4))
        assertEquals(8000L, retryDelayMs(5))
    }

    @Test
    fun `还没重试时不等待、超过 5 次按 8 秒封顶`() {
        assertEquals(0L, retryDelayMs(0))
        assertEquals(0L, retryDelayMs(-1))
        assertEquals(8000L, retryDelayMs(6))
    }
}
