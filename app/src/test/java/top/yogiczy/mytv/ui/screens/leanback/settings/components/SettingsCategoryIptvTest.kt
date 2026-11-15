package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryIptvTest {
    @Test
    fun `channel change flip texts cover both directions`() {
        assertEquals(
            "方向键上：下一个频道；方向键下：上一个频道",
            channelChangeFlipSupportingText(true),
        )
        assertEquals(
            "方向键上：上一个频道；方向键下：下一个频道",
            channelChangeFlipSupportingText(false),
        )
    }
}
