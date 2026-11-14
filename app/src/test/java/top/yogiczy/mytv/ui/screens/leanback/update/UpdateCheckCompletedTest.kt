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

    @Test
    fun `检查进行中 isCheckingUpdate 为 true，查完复位`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<GitRelease>()

        // 第一轮检查卡在拉取上（没回包）
        val first = async {
            viewModel.checkUpdate("2.2.10") {
                started.complete(Unit)
                releaseGate.await()
            }
        }
        started.await()

        // 检查进行中设置页“应用更新”行应显示“检查中”：这档之前
        // 没有任何单测（旧用例只在查完后看状态，取消那个用例也
        // 只断言查完复位 false）；没这行赋值时这里是 false
        assertTrue(viewModel.isCheckingUpdate)

        releaseGate.complete(GitRelease(version = "9.9.9"))
        first.await()
        assertFalse(viewModel.isCheckingUpdate)
        assertTrue(viewModel.checkCompleted)
    }

    @Test
    fun `检查进行中再次检查直接返回不重复拉取`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<GitRelease>()
        var secondFetchCalls = 0

        // 第一轮检查卡在拉取上（没回包）：_isChecking 已置 true
        val first = async {
            viewModel.checkUpdate("2.2.10") {
                started.complete(Unit)
                releaseGate.await()
            }
        }
        started.await()

        // 正在查时再调一次：应直接返回，第二次拉取一次也不调——
        // 这档之前没有任何单测（旧用例全是查完再查，_isChecking
        // 那行守卫一次也没走通过）；没这行守卫时两次拉取会并行跑，
        // 后回来的那次会把先回来的结果盖掉
        viewModel.checkUpdate("2.2.10") {
            secondFetchCalls++
            GitRelease(version = "9.9.9")
        }
        assertEquals(0, secondFetchCalls)

        releaseGate.complete(GitRelease(version = "9.9.9"))
        first.await()
        assertTrue(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)
    }
}
