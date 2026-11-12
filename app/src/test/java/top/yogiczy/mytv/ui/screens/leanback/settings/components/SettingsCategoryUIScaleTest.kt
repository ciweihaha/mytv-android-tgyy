package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCategoryUIScaleTest {
    @Test
    fun `step adds one tenth`() {
        // 短按加一档：1.0 -> 1.1
        assertEquals(1.1f, nextScaleRatio(1.0f), 1e-5f)
        assertEquals(1.5f, nextScaleRatio(1.4f), 1e-5f)
    }

    @Test
    fun `step from just below maximum lands on maximum`() {
        // 1.9 再加一档到顶：浮点累积可能略超 2.0，钳住后正好是 2.0
        assertEquals(2.0f, nextScaleRatio(1.9f), 1e-5f)
    }

    @Test
    fun `step from maximum wraps to minimum`() {
        // 到顶回最小
        assertEquals(1.0f, nextScaleRatio(2.0f), 0.0f)
        // 超过上限也回最小
        assertEquals(1.0f, nextScaleRatio(2.5f), 0.0f)
    }

    @Test
    fun `out of range value below minimum heals to minimum`() {
        // 存了个非法的小值（旧版本/脏数据）：加一档后钳回 1.0，不会一直半大显示
        assertEquals(1.0f, nextScaleRatio(0.5f), 0.0f)
    }

    @Test
    fun `eleven steps from minimum return to minimum`() {
        // 完整一圈：1.0 -> 1.1 -> ... -> 2.0 -> 1.0，共 11 次
        var ratio = 1.0f
        repeat(11) { ratio = nextScaleRatio(ratio) }
        assertEquals(1.0f, ratio, 0.0f)
    }
}
