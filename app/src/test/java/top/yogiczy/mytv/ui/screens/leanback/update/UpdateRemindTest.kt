package top.yogiczy.mytv.ui.screens.leanback.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRemindTest {
    @Test
    fun `remind only for a new version`() {
        // 没更新不提醒
        assertFalse(shouldRemindUpdate(false, "2.2.21", ""))
        assertFalse(shouldRemindUpdate(false, "2.2.21", "2.2.20"))
        // 有更新、但这个版本上次已经提醒过：不再重复提醒
        assertFalse(shouldRemindUpdate(true, "2.2.21", "2.2.21"))
        // 有更新、且是没提醒过的新版本：提醒
        assertTrue(shouldRemindUpdate(true, "2.2.21", "2.2.20"))
        // 从没提醒过（上次版本为空）：提醒
        assertTrue(shouldRemindUpdate(true, "2.2.21", ""))
    }
}
