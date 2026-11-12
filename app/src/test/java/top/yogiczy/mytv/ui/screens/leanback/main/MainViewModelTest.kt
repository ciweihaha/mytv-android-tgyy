package top.yogiczy.mytv.ui.screens.leanback.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.InvalidSourceUrlException

class MainViewModelTest {
    @Test
    fun `same ready snapshot applies epg failure`() {
        val ready = LeanbackMainUiState.Ready()

        assertTrue(shouldApplyEpgFailure(ready, ready))
    }

    @Test
    fun `newer ready with same content does not apply stale epg failure`() {
        // 内容相同但不是同一份对象：中途又刷新了一次，新一轮的 Ready
        // 顶掉了旧快照，过期轮次的失败不能删当前正在用的节目单地址
        val ready = LeanbackMainUiState.Ready()
        val newerReady = LeanbackMainUiState.Ready()

        assertFalse(shouldApplyEpgFailure(newerReady, ready))
    }

    @Test
    fun `loading or error state does not apply epg failure`() {
        val ready = LeanbackMainUiState.Ready()

        assertFalse(shouldApplyEpgFailure(LeanbackMainUiState.Loading(), ready))
        assertFalse(shouldApplyEpgFailure(LeanbackMainUiState.Error("fail"), ready))
    }

    @Test
    fun `format error is not retried`() {
        // 地址格式错重试多少次都一样：报错立刻显示，不用等 10 次重试跑完
        assertFalse(
            shouldRetryLoadFailure(InvalidSourceUrlException("直播源地址格式不正确，请检查地址：x"))
        )
    }

    @Test
    fun `network and other errors are still retried`() {
        assertTrue(shouldRetryLoadFailure(Exception("获取远程直播源失败，请检查网络连接")))
        assertTrue(shouldRetryLoadFailure(IllegalStateException("直播源里没有频道，请检查直播源链接")))
    }
}
