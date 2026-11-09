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

    private suspend fun setCacheData(data: String) = withContext(Dispatchers.IO) {
        val file = getCacheFile()
        file.writeText(data)
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
            ex.printStackTrace()
        }
    }

    protected suspend fun getOrRefresh(
        isExpired: (lastModified: Long, cacheData: String?) -> Boolean,
        refreshOp: suspend () -> String,
    ): String {
        var data = getCacheData()

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
                val staleData = getCacheData()
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