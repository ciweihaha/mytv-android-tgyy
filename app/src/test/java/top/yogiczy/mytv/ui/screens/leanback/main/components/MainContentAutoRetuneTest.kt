package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.media3.common.PlaybackException as Media3PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

class MainContentAutoRetuneTest {
    @Test
    fun `2004 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ),
        )
    }

    @Test
    fun `2001 和 2002 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
        )
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ),
        )
    }

    @Test
    fun `BEHIND_LIVE_WINDOW 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            ),
        )
    }

    @Test
    fun `解码错不自动重调`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ),
        )
    }

    @Test
    fun `解析错不自动重调——PARSING_MANIFEST_MALFORMED 不在重试之列`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            ),
        )
    }

    @Test
    fun `加载超时和断流不自动重调`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT.errorCode,
            ),
        )
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                LeanbackVideoPlayer.PlaybackException.CUTOFF.errorCode,
            ),
        )
    }

    @Test
    fun `没有错误码时不自动重调`() {
        assertFalse(shouldAutoRetuneAfterExhaustion(null))
    }

    @Test
    fun `自动重调等 5 秒、不超过 10 秒上限`() {
        assertEquals(5000L, LeanbackMainContentState.AUTO_RETUNE_DELAY_MS)
        assertEquals(10000L, LeanbackMainContentState.AUTO_RETUNE_MAX_WAIT_MS)
        assertTrue(
            LeanbackMainContentState.AUTO_RETUNE_DELAY_MS <=
                LeanbackMainContentState.AUTO_RETUNE_MAX_WAIT_MS,
        )
    }
}
