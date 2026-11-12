package top.yogiczy.mytv.ui.screens.leanback.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.repositories.iptv.EmptyIptvSourceException
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
    fun `empty source error is not retried`() {
        // 直播源里没频道：拉到的内容解析前已写进缓存，重试读到的都是
        // 同一份缓存、解析出同样的 0 个频道，报错立刻显示
        assertFalse(
            shouldRetryLoadFailure(EmptyIptvSourceException("直播源里没有频道，请检查直播源链接"))
        )
        assertFalse(
            shouldRetryLoadFailure(
                EmptyIptvSourceException("精简后没有频道：该直播源里没有央视或卫视台，请关闭精简模式或换源")
            )
        )
    }

    @Test
    fun `network and other errors are still retried`() {
        assertTrue(shouldRetryLoadFailure(Exception("获取远程直播源失败，请检查网络连接")))
        // 其他 IllegalStateException（不是空源那个）照常重试
        assertTrue(shouldRetryLoadFailure(IllegalStateException("下载响应体为空")))
    }

    @Test
    fun `failed url is removed from history only on deterministic errors`() {
        // 地址本身有问题（格式错、源里没频道）才删
        assertTrue(
            shouldRemoveFailedUrlFromHistory(
                InvalidSourceUrlException("直播源地址格式不正确，请检查地址：x")
            )
        )
        assertTrue(
            shouldRemoveFailedUrlFromHistory(
                EmptyIptvSourceException("直播源里没有频道，请检查直播源链接")
            )
        )
        // 网络抖一下就失败：地址本身没问题，留着，下次还能从历史里点
        assertFalse(
            shouldRemoveFailedUrlFromHistory(Exception("获取远程直播源失败，请检查网络连接"))
        )
        assertFalse(shouldRemoveFailedUrlFromHistory(IllegalStateException("下载响应体为空")))
    }
}
