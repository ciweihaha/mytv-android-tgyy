package top.yogiczy.mytv.ui.screens.leanback.toast

import org.junit.Assert.assertFalse
import org.junit.Test

class LeanbackToastStateTest {
    @Test
    fun `global instance is uninitialized before first composition`() {
        // I 只在 rememberLeanbackToastState 合成时赋值；单元测试里没
        // 有合成，所以这里一定未初始化——LeanbackActivity 回调里的
        // isInitialized 守卫就依赖这个前提
        assertFalse(LeanbackToastState::I.isInitialized)
    }
}
