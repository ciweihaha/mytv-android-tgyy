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
    fun `cancelled recheck clears stale checkFailed`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<GitRelease>()

        viewModel.checkUpdate("2.2.10") { throw RuntimeException("network down") }
        assertTrue(viewModel.checkFailed)

        // 第二轮检查卡在拉取上、随后取消：开始时就把旧的失败标记清掉了，
        // 取消不是失败、不会再置回去——只断查完成功那档测不出来（2242 轮
        // 是成功后复位），只断全新状态取消那档也测不出来（取消时
        // checkFailed 本来就是 false）
        val check = async {
            viewModel.checkUpdate("2.2.10") {
                started.complete(Unit)
                releaseGate.await()
            }
        }
        started.await()
        assertFalse(viewModel.checkFailed)
        check.cancel()
        try {
            check.await()
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            // 预期：取消原样抛出来。文案不断——这里的取消是外面调
            // check.cancel() 触发的，框架给的文案是 "Job was cancelled"，
            // 不是测试自己抛的 "cancelled"；断文案等于断框架措辞
        }
        assertFalse(viewModel.checkFailed)
        assertFalse(viewModel.checkCompleted)
        // 取消的重查不改已知状态：失败那次本来就没查到更新，取消后仍是没有
        // （壳 965 轮同款断言）
        assertFalse(viewModel.isUpdateAvailable)
    }

    @Test
    fun `cancelled recheck after success keeps known update`() = runBlocking {
        val viewModel = LeanBackUpdateViewModel()
        val started = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<GitRelease>()

        viewModel.checkUpdate("2.2.10") { GitRelease(version = "9.9.9") }
        assertTrue(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)
        assertEquals("9.9.9", viewModel.latestRelease.version)

        // 第二轮检查卡在拉取上、随后取消：取消不是失败，查到的更新不能丢——
        // catch 不改 _latestRelease / _isUpdateAvailable，取消那条路连 catch
        // 都不进（原样抛）；只断失败重查那档（2239 轮）测不出取消这条路，
        // 只断全新状态取消那档也测不出来（那时本来就没已知更新）
        val check = async {
            viewModel.checkUpdate("2.2.10") {
                started.complete(Unit)
                releaseGate.await()
            }
        }
        started.await()
        assertFalse(viewModel.checkFailed)
        check.cancel()
        try {
            check.await()
            org.junit.Assert.fail("取消时应原样抛 CancellationException")
        } catch (ex: CancellationException) {
            // 预期：取消原样抛出来，文案不断（2244 轮修法教训）
        }
        assertFalse(viewModel.checkFailed)
        assertFalse(viewModel.checkCompleted)
        assertTrue(viewModel.isUpdateAvailable)
        assertEquals("9.9.9", viewModel.latestRelease.version)
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
