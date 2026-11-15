package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.PlaybackException as Media3PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3BehindLiveWindowRetryTest {
    @Test
    fun `BEHIND_LIVE_WINDOW 连报三次都重试、第四次上报`() {
        val errorCode = Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        var retries = 0
        repeat(3) {
            assertTrue(shouldRetryBehindLiveWindow(errorCode, retries))
            retries++
        }
        assertFalse(shouldRetryBehindLiveWindow(errorCode, retries))
    }

    @Test
    fun `其他错误码一次也不重试`() {
        assertFalse(
            shouldRetryBehindLiveWindow(
                Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                0,
            ),
        )
        assertFalse(
            shouldRetryBehindLiveWindow(
                Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                2,
            ),
        )
    }
}
