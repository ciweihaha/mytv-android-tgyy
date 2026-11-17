package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
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

    @Test
    fun `tag 没给帧率时用一秒内实测帧数补`() {
        assertEquals(24f, supplementFrameRate(0f, 24), 0.001f)
    }

    @Test
    fun `卡顿时帧数为零留着旧值不清零`() {
        assertEquals(0f, supplementFrameRate(0f, 0), 0.001f)
    }

    @Test
    fun `tag 给了帧率就不动实测值`() {
        assertEquals(25f, supplementFrameRate(25f, 24), 0.001f)
    }
}
