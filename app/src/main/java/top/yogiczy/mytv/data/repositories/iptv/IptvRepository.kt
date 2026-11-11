package top.yogiczy.mytv.data.repositories.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.data.repositories.FileCacheRepository
import top.yogiczy.mytv.data.repositories.iptv.parser.IptvParser
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.utils.Logger

/**
 * 直播源获取
 */
class IptvRepository : FileCacheRepository("iptv.txt") {
    private val log = Logger.create(javaClass.simpleName)

    /**
     * 获取远程直播源数据
     */
    private suspend fun fetchSource(sourceUrl: String) = withContext(Dispatchers.IO) {
        log.d("获取远程直播源: $sourceUrl")

        val request = Request.Builder().url(sourceUrl).build()

        try {
            // 响应体全路径都关：原来只有成功读 body 那条路关了（string()
            // 会关流），服务器出错（404/500）或响应体为空时直接抛错、
            // 响应体根本没关，连接回不到连接池；直播源一挂就反复失败
            // 几次，占满单 host 的连接槽后后续拉取一直等连接。写法与
            // Downloader（第 180 轮）、节目单拉取（第 181 轮）一致
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("获取远程直播源失败: ${response.code}")
                }

                response.body?.string()
                    ?: throw Exception("获取远程直播源失败: 响应体为空")
            }
        } catch (ex: Exception) {
            log.e("获取远程直播源失败", ex)
            throw Exception("获取远程直播源失败，请检查网络连接", ex)
        }
    }

    /**
     * 简化规则
     */
    private fun simplifyTest(iptv: Iptv): Boolean {
        return iptv.name.startsWith("cctv", ignoreCase = true) || iptv.name.endsWith("卫视")
    }

    /**
     * 获取直播源分组列表
     */
    suspend fun getIptvGroupList(
        sourceUrl: String,
        cacheTime: Long,
        simplify: Boolean = false,
    ): IptvGroupList {
        try {
            val sourceData = getOrRefresh(cacheTime) {
                fetchSource(sourceUrl)
            }

            val parser = IptvParser.instances.first { it.isSupport(sourceUrl, sourceData) }
            val groupList = parser.parse(sourceData)
            // 只数个数不用先拼一份全量列表：原先 flatMap 把全部频道先拷
            // 一份再数，频道上千时白白多一份大列表
            log.i("解析直播源完成：${groupList.size}个分组，${groupList.sumOf { it.iptvList.size }}个频道")

            val result = if (simplify) {
                IptvGroupList(groupList.map { group ->
                    IptvGroup(
                        name = group.name, iptvList = IptvList(group.iptvList.filter { iptv ->
                            simplifyTest(iptv)
                        })
                    )
                }.filter { it.iptvList.isNotEmpty() })
            } else {
                groupList
            }

            // 空源直接报错：原来解析出 0 个频道也进 Ready，主界面黑屏、
            // 上下键也切不到台，又没有任何提示；现在走报错那条路，错误页
            // 有重试按钮和换源二维码
            checkIptvGroupListNotEmpty(result, simplify)
            return result
        } catch (ex: Exception) {
            log.e("获取直播源失败", ex)
            throw Exception(ex)
        }
    }
}

/**
 * 直播源里一个频道都没有时报错：空文件、或精简模式把台全筛掉了。
 * 顶层函数方便单元测试直接调用。
 */
internal fun checkIptvGroupListNotEmpty(groupList: IptvGroupList, simplified: Boolean) {
    if (groupList.sumOf { it.iptvList.size } == 0) {
        throw IllegalStateException(
            if (simplified) "精简后没有频道：该直播源里没有央视或卫视台，请关闭精简模式或换源"
            else "直播源里没有频道，请检查直播源链接"
        )
    }
}