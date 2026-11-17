package top.yogiczy.mytv.ui.screens.leanback.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelSpecialListModeTest {
    @Test
    fun `from all list goes to favorites first`() {
        assertEquals(1, nextSpecialListMode(0, hasFavorites = true, hasRecents = true))
        assertEquals(1, nextSpecialListMode(0, hasFavorites = true, hasRecents = false))
    }

    @Test
    fun `from all list goes to recents when no favorites`() {
        assertEquals(2, nextSpecialListMode(0, hasFavorites = false, hasRecents = true))
    }

    @Test
    fun `from all list stays when neither exists`() {
        assertEquals(0, nextSpecialListMode(0, hasFavorites = false, hasRecents = false))
    }

    @Test
    fun `favorites cycles to recents then back to all`() {
        assertEquals(2, nextSpecialListMode(1, hasFavorites = true, hasRecents = true))
        assertEquals(0, nextSpecialListMode(1, hasFavorites = true, hasRecents = false))
        assertEquals(0, nextSpecialListMode(2, hasFavorites = true, hasRecents = true))
    }

    @Test
    fun `unknown mode falls back to favorites or recents`() {
        assertEquals(1, nextSpecialListMode(99, hasFavorites = true, hasRecents = false))
        assertEquals(0, nextSpecialListMode(99, hasFavorites = false, hasRecents = false))
    }
}
