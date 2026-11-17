package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.PlaybackException as Media3PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.Constants

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

    @Test
    fun `单次退避最长 8 秒、短于 15 秒加载超时`() {
        // 退避期间旧的 watchdog 还在跑：报错后走重试那路不调
        // triggerError（只有上报错误那路才调，调了才取消旧 watchdog），
        // 重试只在 prepare() 后才调 triggerPrepared 重启它——单次退避
        // 必须短于加载超时，否则报错一来就撞上旧 watchdog、连第 1 次
        // 退避都等不完。注意这只保“报错刚发生时”那档——报错来得晚、
        // 旧 watchdog 剩下的时间比本次退避短，照样先报假 LOAD_TIMEOUT
        // （壳 1036 轮同款）。档位表只钉了各自的数，没钉
        // 两数的关系——以后有人把超时改短、或把退避封顶抬上去，这里先响。
        assertTrue(retryDelayMs(1) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
        assertTrue(retryDelayMs(2) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
        assertTrue(retryDelayMs(3) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
        assertTrue(retryDelayMs(4) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
        assertTrue(retryDelayMs(5) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
        assertTrue(retryDelayMs(6) < Constants.VIDEO_PLAYER_LOAD_TIMEOUT)
    }
}
