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

    @Test
    fun `iptv source cache time texts cover special values`() {
        assertEquals("不缓存", iptvSourceCacheTimeText(0L))
        assertEquals("永久", iptvSourceCacheTimeText(Long.MAX_VALUE))
    }

    @Test
    fun `iptv source cache time texts use humanizeMs for normal values`() {
        assertEquals("1分钟", iptvSourceCacheTimeText(60_000L))
        assertEquals("1小时", iptvSourceCacheTimeText(3_600_000L))
    }
}
