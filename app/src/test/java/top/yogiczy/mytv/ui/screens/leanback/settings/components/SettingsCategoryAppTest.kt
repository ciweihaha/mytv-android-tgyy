package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryAppTest {
    @Test
    fun `update texts show four states`() {
        // 没查过
        assertEquals("尚未检查，点击手动检查", updateSupportingText(false, false, false, false, ""))
        assertEquals("未检查", updateTrailingText(false, false, false, false))
        // 检查中
        assertEquals("正在检查…", updateSupportingText(false, true, false, false, ""))
        assertEquals("检查中", updateTrailingText(false, true, false, false))
        // 查完无更新
        assertEquals("已是最新版本", updateSupportingText(false, false, false, true, "2.2.20"))
        assertEquals("无更新", updateTrailingText(false, false, false, true))
        // 查完有更新
        assertEquals("最新版本：v2.2.21", updateSupportingText(true, false, false, true, "2.2.21"))
        assertEquals("发现新版本", updateTrailingText(true, false, false, true))
    }

    @Test
    fun `known update wins over checking`() {
        // 重查时旧的“有更新”仍在：检查中也不盖掉它
        assertEquals("最新版本：v2.2.21", updateSupportingText(true, true, false, false, "2.2.21"))
        assertEquals("发现新版本", updateTrailingText(true, true, false, false))
    }

    @Test
    fun `update texts show check failure`() {
        // 查失败了：行里要留档，不能谎称“尚未检查”——失败和从未检查是两回事
        assertEquals("检查更新失败，点击重试", updateSupportingText(false, false, true, false, ""))
        assertEquals("检查失败", updateTrailingText(false, false, true, false))
    }

    @Test
    fun `known update wins over check failure`() {
        // 重查失败也不丢已经知道的更新：失败不改 isUpdateAvailable（原有约定）
        assertEquals("最新版本：v2.2.21", updateSupportingText(true, false, true, false, "2.2.21"))
        assertEquals("发现新版本", updateTrailingText(true, false, true, false))
    }
}
