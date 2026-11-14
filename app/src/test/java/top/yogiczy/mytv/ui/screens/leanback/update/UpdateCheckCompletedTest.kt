package top.yogiczy.mytv.ui.screens.leanback.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.GitRelease

class UpdateCheckCompletedTest {
    @Test
    fun `failed recheck clears stale checkCompleted`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        // 先成功查一次：有新版
        viewModel.checkUpdate("2.2.10") { GitRelease(version = "9.9.9") }
        assertTrue(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)

        // 再查一次、这次失败（断网）：旧的成功标记必须清掉，否则设置页
        // “应用更新”行还显示“已是最新版本”，手动重查失败的提示也是
        // “已是最新版本”而不是“检查更新失败”
        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }
        assertFalse(viewModel.checkCompleted)
        // 已知道的更新不丢：失败不改 isUpdateAvailable（原有约定）
        assertTrue(viewModel.isUpdateAvailable)
    }

    @Test
    fun `first check failing leaves checkCompleted false`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }

        assertFalse(viewModel.checkCompleted)
        assertFalse(viewModel.isUpdateAvailable)
    }

    @Test
    fun `successful check with no update marks completed`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { GitRelease(version = "2.2.10") }

        assertTrue(viewModel.checkCompleted)
        assertFalse(viewModel.isUpdateAvailable)
    }

    @Test
    fun `successful check with update marks completed and available`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { GitRelease(version = "9.9.9") }

        assertTrue(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)
    }

    @Test
    fun `cancelled check propagates cancellation instead of logging failure`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        // 检查中途离开更新页、LaunchedEffect 取消：取消异常不能被
        // 当成普通检查失败记一条日志、更不能吞掉；没这行守卫时这里
        // 不抛异常，只记一条“检查更新失败”
        try {
            viewModel.checkUpdate("2.2.10") { throw CancellationException("cancelled") }
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            assertEquals("cancelled", ex.message)
        }

        assertFalse(viewModel.checkCompleted)
        assertFalse(viewModel.isCheckingUpdate)
    }
}
