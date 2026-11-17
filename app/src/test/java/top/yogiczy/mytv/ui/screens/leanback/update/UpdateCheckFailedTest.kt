package top.yogiczy.mytv.ui.screens.leanback.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.GitRelease

class UpdateCheckFailedTest {
    @Test
    fun `first check failing marks checkFailed`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }

        assertTrue(viewModel.checkFailed)
        assertFalse(viewModel.checkCompleted)
        assertFalse(viewModel.isUpdateAvailable)
    }

    @Test
    fun `successful check after failure clears checkFailed`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }
        assertTrue(viewModel.checkFailed)

        viewModel.checkUpdate("2.2.10") { GitRelease(version = "2.2.10") }

        assertFalse(viewModel.checkFailed)
        assertTrue(viewModel.checkCompleted)
    }

    @Test
    fun `failed recheck after success marks checkFailed and keeps known update`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        viewModel.checkUpdate("2.2.10") { GitRelease(version = "9.9.9") }
        assertTrue(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }

        assertTrue(viewModel.checkFailed)
        assertFalse(viewModel.checkCompleted)
        // 已知道的更新不丢：失败不改 isUpdateAvailable（原有约定）
        assertTrue(viewModel.isUpdateAvailable)
    }

    @Test
    fun `new check start clears stale checkFailed while in flight`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<GitRelease>()

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }
        assertTrue(viewModel.checkFailed)

        // 新一轮检查卡在拉取上：开始时就把旧的失败标记清掉，查的过程中
        // 设置页不应还显示“检查更新失败”；只断查完后的值测不出来——
        // 成功后复位那档已有用例钉死，中间这档一次也没断言过
        val check = async {
            viewModel.checkUpdate("2.2.10") {
                started.complete(Unit)
                releaseGate.await()
            }
        }
        started.await()
        assertFalse(viewModel.checkFailed)
        assertTrue(viewModel.isCheckingUpdate)

        releaseGate.complete(GitRelease(version = "9.9.9"))
        check.await()
        assertFalse(viewModel.checkFailed)
        assertTrue(viewModel.checkCompleted)
    }

    @Test
    fun `cancelled check does not mark checkFailed`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()

        try {
            viewModel.checkUpdate("2.2.10") { throw CancellationException("cancelled") }
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            assertEquals("cancelled", ex.message)
        }

        // 取消不是失败：检查中途离开更新页不能把那行变成“检查失败”
        assertFalse(viewModel.checkFailed)
        assertFalse(viewModel.checkCompleted)
    }
}
