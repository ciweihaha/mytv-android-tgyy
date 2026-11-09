package top.yogiczy.mytv.data.repositories.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRepositoryTest {
    @Test
    fun `unsupported update source throws before any network call`() {
        // 不支持的更新源必须在联网前就抛 IllegalArgumentException；
        // 放进网络 try 里会被包成“请检查网络连接”，配置错了也误导
        // 用户去查网络（第 73 轮只改了内层异常，外层照包不误）
        var thrown: Throwable? = null
        runBlocking {
            try {
                GitRepository().latestRelease("https://example.com/releases/latest")
            } catch (e: Exception) {
                thrown = e
            }
        }
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown?.message?.contains("不支持的更新源") == true)
    }
}
