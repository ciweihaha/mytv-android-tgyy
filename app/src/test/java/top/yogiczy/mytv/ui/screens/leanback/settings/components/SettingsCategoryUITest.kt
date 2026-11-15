package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.ui.utils.SP

class SettingsCategoryUITest {
    @Test
    fun `time show texts cover four modes`() {
        assertEquals("不显示时间", timeShowSupportingText(SP.UiTimeShowMode.HIDDEN, 30L))
        assertEquals("总是显示时间", timeShowSupportingText(SP.UiTimeShowMode.ALWAYS, 30L))
        assertEquals("整点前后30s显示时间", timeShowSupportingText(SP.UiTimeShowMode.EVERY_HOUR, 30L))
        assertEquals("半点前后30s显示时间", timeShowSupportingText(SP.UiTimeShowMode.HALF_HOUR, 30L))
    }

    @Test
    fun `time show range seconds are interpolated`() {
        // 秒数是动态的：改了 UI_TIME_SHOW_RANGE 就跟着变
        assertEquals("整点前后60s显示时间", timeShowSupportingText(SP.UiTimeShowMode.EVERY_HOUR, 60L))
        assertEquals("半点前后60s显示时间", timeShowSupportingText(SP.UiTimeShowMode.HALF_HOUR, 60L))
    }
}
