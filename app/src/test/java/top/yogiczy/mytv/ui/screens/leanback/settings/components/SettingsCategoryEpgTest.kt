package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryEpgTest {
    @Test
    fun `refresh time threshold texts cover boundaries`() {
        assertEquals("0:00", epgRefreshTimeThresholdText(0))
        assertEquals("5:00", epgRefreshTimeThresholdText(5))
        assertEquals("12:00", epgRefreshTimeThresholdText(12))
    }

    @Test
    fun `refresh time threshold hint interpolates threshold`() {
        assertEquals(
            "短按增加1小时，长按设为0小时；时间不到5:00节目单将不会刷新",
            epgRefreshTimeThresholdHintText(5),
        )
        assertEquals(
            "短按增加1小时，长按设为0小时；时间不到0:00节目单将不会刷新",
            epgRefreshTimeThresholdHintText(0),
        )
    }
}
