package top.yogiczy.mytv.data.repositories

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal

class FileCacheRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestCacheRepo(fileName: String) : FileCacheRepository(fileName) {
        suspend fun load(cacheTime: Long, refreshOp: suspend () -> String) =
            getOrRefresh(cacheTime, refreshOp)

        suspend fun cached() = getCachedData()
    }

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    @Test
    fun `refresh writes complete data that reads back`() = runBlocking {
        val repo = TestCacheRepo("test.txt")

        repo.load(0) { "hello cache" }

        assertEquals("hello cache", repo.cached())
    }

    @Test
    fun `concurrent refreshes never leave mixed content`() = runBlocking {
        val repo = TestCacheRepo("test.txt")
        // 每个刷新写 200KB 的单一字符：直接 writeText 并发写会交叉
        // 成混合内容；原子改名只会留下一份完整的
        val payloads = List(20) { i -> (('a' + i).toChar()).toString().repeat(200_000) }

        payloads.map { payload ->
            launch { repo.load(0) { payload } }
        }.forEach { it.join() }

        val cached = repo.cached()
        assertTrue(cached != null && cached in payloads)
    }
}
