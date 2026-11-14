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
    fun `2007 和 2008 是瞬时网络错`() {
        assertEquals(2007, Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(2008, Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
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
    fun `2004 连报三次都重试、第四次上报`() {
        val errorCode = Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        var retries = 0
        repeat(3) {
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
}
