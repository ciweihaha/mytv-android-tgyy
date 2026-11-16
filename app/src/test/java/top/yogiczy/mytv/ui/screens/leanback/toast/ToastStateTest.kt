package top.yogiczy.mytv.ui.screens.leanback.toast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `换场后没有更新的提示新提示正常显示`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        try {
            state.showToast("A")
            delay(200)
            assertTrue(state.visible)
            assertEquals("A", state.current.message)

            // 只有 B：B 先藏起 A、等 300ms 换场，醒来时号没过期，
            // 必须接着把 B 显示出来——这条“号没过期就继续显示”
            // 的路之前没有任何单测（旧用例里换场后总有更新的 C
            // 把 B 盖掉，B 的这条路一次也没走通过）
            state.showToast("B")
            delay(700)
            assertEquals("B", state.current.message)
            assertTrue(state.visible)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `提示到期后自动藏起`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        // observe() 之前没有任何单测：它只在 rememberLeanbackToastState
        // 里 LaunchedEffect 调，防抖到期自动藏起这条路一次也没走通过——
        // 如果 debounce 那行丢了，提示会一直挂在屏幕上，旧单测全过、
        // 发现不了
        val observeJob = scope.launch { state.observe() }
        try {
            state.showToast("A")
            delay(200)
            assertTrue(state.visible)

            // 默认时长 2300ms：等防抖到期，提示自己藏起
            delay(2800)
            assertFalse(state.visible)
        } finally {
            observeJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `自定义时长按自定义毫秒藏起`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        val observeJob = scope.launch { state.observe() }
        try {
            // Custom(500)：toMs 必须回 500、不是默认 2300——更新页
            // 在用 Custom(10_000)，这档一次也没测过
            state.showToast("A", duration = LeanbackToastProperty.Duration.Custom(500))
            delay(200)
            assertTrue(state.visible)

            // 500 毫秒防抖到期就藏起；若 toMs 错回默认 2300，
            // 这里还会是可见，单测会失败
            delay(800)
            assertFalse(state.visible)
        } finally {
            observeJob.cancel()
            scope.cancel()
        }
    }

    @Test
    fun `到期藏起后再发新提示仍能正常显示并到期藏起`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val state = LeanbackToastState(scope)
        val observeJob = scope.launch { state.observe() }
        try {
            state.showToast("A", duration = LeanbackToastProperty.Duration.Custom(500))
            delay(200)
            assertTrue(state.visible)

            delay(800)
            assertFalse(state.visible)

            // 再发一条：应正常显示、到期再藏起；observe 若只取一次
            // 就停，第二条到期也不会藏起
            state.showToast("B", duration = LeanbackToastProperty.Duration.Custom(500))
            delay(200)
            assertTrue(state.visible)
            assertEquals("B", state.current.message)

            delay(800)
            assertFalse(state.visible)
        } finally {
            observeJob.cancel()
            scope.cancel()
        }
    }
}
