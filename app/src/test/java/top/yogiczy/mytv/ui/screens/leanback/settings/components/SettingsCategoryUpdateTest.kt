package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryUpdateTest {
    @Test
    fun updateForceRemindSupportingText_bothStates() {
        assertEquals(
            "检测到新版本时会弹窗提醒",
            updateForceRemindSupportingText(true),
        )
        assertEquals(
            "检测到新版本时仅用提示条提示",
            updateForceRemindSupportingText(false),
        )
    }
}
