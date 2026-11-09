package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Media3MetadataNoValueTest {
    @Test
    fun `int no-value becomes zero`() {
        assertEquals(0, (-1).noValueToZero())
        assertEquals(1920, 1920.noValueToZero())
        assertEquals(0, 0.noValueToZero())
    }

    @Test
    fun `float no-value becomes zero`() {
        assertEquals(0f, (-1f).noValueToZero())
        assertEquals(25f, 25f.noValueToZero())
        assertEquals(0f, 0f.noValueToZero())
    }
}
