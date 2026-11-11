package top.yogiczy.mytv.data.repositories.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yogiczy.mytv.data.repositories.git.parser.GitReleaseParser
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.utils.Loggable

class GitRepository : Loggable() {

    suspend fun latestRelease(url: String) = withContext(Dispatchers.IO) {
        // 先校验更新源：不支持的地址直接抛 IllegalArgumentException，
        // 不能放进下面网络的 try 里，否则会被包成“请检查网络连接”，
        // 配置错了也误导用户去查网络；放 log 之前，单测不碰 android.util.Log
        val parser = GitReleaseParser.selectParser(url)
            ?: throw IllegalArgumentException("不支持的更新源，仅支持 GitHub / Gitee：$url")

        log.d("获取最新发行版: $url")

        val request = Request.Builder().url(url).build()

        try {
            // 响应体全路径都关：原来只有成功读 body 那条路关了（string()
            // 会关流），服务器出错（404/500）或响应体为空时直接抛错、
            // 响应体根本没关，连接回不到连接池；更新源一挂就反复失败
            // 几次，占满单 host 的连接槽后后续检查一直等连接。写法与
            // Downloader（第 180 轮）、节目单拉取（第 181 轮）一致
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("获取最新发行版失败: ${response.code}")
                }

                parser.parse(
                    response.body?.string()
                        ?: throw Exception("获取最新发行版失败: 响应体为空")
                )
            }
        } catch (ex: Exception) {
            log.e("获取最新发行版失败", ex)
            throw Exception("获取最新发行版失败，请检查网络连接", ex)
        }
    }
}