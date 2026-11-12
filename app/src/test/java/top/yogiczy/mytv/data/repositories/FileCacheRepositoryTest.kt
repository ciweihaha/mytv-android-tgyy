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
    fun `refresh failure reuses stale cache read at start`() = runBlocking {
        val repo = TestCacheRepo("test.txt")
        repo.load(0) { "stale but usable" }

        // 缓存已过期（cacheTime 为 0 一律过期），刷新又失败：应直接
        // 拿开头读到的那份旧缓存顶上，而不是再读一遍磁盘
        val result = repo.load(0) { throw RuntimeException("network down") }

        assertEquals("stale but usable", result)
    }

    @Test
    fun `refresh failure without cache still throws`() = runBlocking {
        val repo = TestCacheRepo("test.txt")

        try {
            repo.load(0) { throw RuntimeException("network down") }
            org.junit.Assert.fail("没有旧缓存时刷新失败应抛错")
        } catch (ex: RuntimeException) {
            assertEquals("network down", ex.message)
        }
    }

    @Test
    fun `refresh returning blank reuses stale cache and leaves it intact`() = runBlocking {
        val repo = TestCacheRepo("test.txt")
        repo.load(0) { "stale but usable" }

        // 服务器回 200 空体不抛错：空结果不算刷新成功，应按刷新失败
        // 走旧缓存顶上，而不是把好缓存盖成空
        val result = repo.load(0) { "" }

        assertEquals("stale but usable", result)
        assertEquals("stale but usable", repo.cached())
    }

    @Test
    fun `refresh returning blank without cache throws and writes nothing`() = runBlocking {
        val repo = TestCacheRepo("test.txt")

        try {
            repo.load(0) { "   " }
            org.junit.Assert.fail("没有旧缓存时刷新回空串应抛错")
        } catch (ex: Exception) {
            assertEquals("刷新结果为空", ex.message)
        }
        assertEquals(null, repo.cached())
    }

    @Test
    fun `unreadable cache file reads as no cache`() = runBlocking {
        val repo = TestCacheRepo("test.txt")
        // 缓存位置上是个目录：exists() 为真、readText() 抛错，模拟换源
        // 清缓存正好撞上另一路正在读缓存
        assertTrue(tempFolder.newFolder("test.txt").isDirectory)

        assertEquals(null, repo.cached())
    }

    @Test
    fun `unreadable cache does not block refresh attempt`() = runBlocking {
        val repo = TestCacheRepo("test.txt")
        assertTrue(tempFolder.newFolder("test.txt").isDirectory)

        var refreshAttempted = false
        try {
            // 读缓存失败不应拦住刷新：刷新操作必须跑一次；写缓存会因
            // 目录占位失败而抛错（那是另一回事），这里只断言刷新跑过
            repo.load(0) {
                refreshAttempted = true
                "fresh data"
            }
        } catch (_: Exception) {
            // 写缓存失败时抛错是预期内的，不影响本用例的断言
        }

        assertTrue(refreshAttempted)
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
