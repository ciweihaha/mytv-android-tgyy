package top.yogiczy.mytv.ui.screens.leanback.toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastStateTest {
    @Test
    fun `换场时又来新提示最新那条不被旧的盖回去`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        try {
            state.showToast("A")
            delay(200)
            assertTrue(state.visible)
            assertEquals("A", state.current.message)

            // B 先到：藏起 A、等 300ms 换场；C 紧跟着到：A 已藏起，
            // 直接显示。B 醒来时号已过期，不能再把 C 盖成 B
            state.showToast("B")
            state.showToast("C")
            delay(600)
            assertEquals("C", state.current.message)
            assertTrue(state.visible)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `同 id 的提示直接刷新内容不走换场`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        try {
            state.showToast("旧内容", id = "same")
            delay(200)
            state.showToast("新内容", id = "same")
            delay(200)
            assertEquals("新内容", state.current.message)
            assertTrue(state.visible)
        } finally {
            scope.cancel()
        }
    }
}
