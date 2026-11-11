package top.yogiczy.mytv.data.repositories

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.utils.Logger
import java.io.File

/**
 * 用于将数据缓存至本地
 */
abstract class FileCacheRepository(
    private val fileName: String,
) {
    private val log = Logger.create(javaClass.simpleName)
    private fun getCacheFile() = File(AppGlobal.cacheDir, fileName)

    private suspend fun getCacheData(): String? = withContext(Dispatchers.IO) {
        val file = getCacheFile()
        if (file.exists()) file.readText()
        else null
    }

    /**
     * 只读本地缓存，不触发刷新：供"未到刷新时间点"等场景直接用旧数据
     */
    protected suspend fun getCachedData(): String? = getCacheData()

    private suspend fun setCacheData(data: String) = withContext(Dispatchers.IO) {
        val file = getCacheFile()
        // 原子写：先写唯一临时文件再改名，写一半被杀也不会留下截断的
        // 缓存；否则下次加载读到半截数据，解析失败要等缓存过期才恢复
        // （第 67 轮只在 EPG 解码层做了容错，这里是根因，三个缓存
        // iptv.txt / epg.json / epg.xml 一起受益）。临时文件名唯一，
        // 并发刷新也不会往同一个文件里交叉写，最后改名的那个胜出，
        // 文件永远是完整的
        val tempFile = File.createTempFile("${file.name}_", ".tmp", file.parentFile)
        try {
            tempFile.writeText(data)
            if (!tempFile.renameTo(file)) {
                // 改名失败时回退到直接写（至少新数据能落盘）
                tempFile.delete()
                file.writeText(data)
            }
        } catch (ex: Exception) {
            tempFile.delete()
            throw ex
        }
    }

    protected suspend fun getOrRefresh(cacheTime: Long, refreshOp: suspend () -> String): String {
        return getOrRefresh(
            { lastModified, _ -> System.currentTimeMillis() - lastModified >= cacheTime },
            refreshOp,
        )
    }

    fun clearCache() {
        try {
            getCacheFile().delete()
        } catch (ex: Exception) {
            // 别只 printStackTrace：失败只进 logcat stderr，应用内的
            // 日志历史里看不到，用户反馈“清缓存没反应”时无据可查
            log.w("清除缓存失败", ex)
        }
    }

    protected suspend fun getOrRefresh(
        isExpired: (lastModified: Long, cacheData: String?) -> Boolean,
        refreshOp: suspend () -> String,
    ): String {
        // 开头读到的旧缓存先记下来：过期时下面要丢掉它、刷新失败时又要
        // 拿它顶上。原先失败时重新从磁盘读一整遍，节目单 epg.json /
        // epg.xml 动辄几百 KB 到几 MB，白白多一次大文件读；现在直接
        // 用开头那份。只有开头没读到东西时才再读一次——并发的另一路
        // 刷新可能刚把文件写好，那份新的完整缓存仍值得捡回来
        val cachedData = getCacheData()
        var data = cachedData

        if (isExpired(getCacheFile().lastModified(), data)) {
            data = null
        }

        if (data.isNullOrBlank()) {
            try {
                data = refreshOp()
                setCacheData(data)
            } catch (ex: Exception) {
                // 刷新失败时用旧缓存顶上（若有）：源服务器临时故障也不至于
                // 无频道可看；换源时调用方已先清缓存，所以旧缓存只可能是
                // 同源的。注意不重写缓存文件：时间戳不变，下次加载仍会重试刷新
                val staleData = if (!cachedData.isNullOrBlank()) cachedData else getCacheData()
                if (!staleData.isNullOrBlank()) {
                    log.w("刷新失败，使用旧缓存", ex)
                    return staleData
                }
                throw ex
            }
        }

        return data
    }
}