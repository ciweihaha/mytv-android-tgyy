package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PositionPollingTest {
    @Test
    fun `ended stream stops the position polling loop`() {
        assertTrue(shouldStopPositionPolling(Player.STATE_ENDED))
    }

    @Test
    fun `idle player stops the position polling loop`() {
        assertTrue(shouldStopPositionPolling(Player.STATE_IDLE))
    }

    @Test
    fun `ready keeps polling to watch for stalls`() {
        assertFalse(shouldStopPositionPolling(Player.STATE_READY))
    }

    @Test
    fun `buffering keeps polling to watch for stalls`() {
        assertFalse(shouldStopPositionPolling(Player.STATE_BUFFERING))
    }
}
