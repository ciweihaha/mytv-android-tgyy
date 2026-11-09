package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yogiczy.mytv.data.entities.GitRelease

/**
 * git发行版解析
 */
interface GitReleaseParser {
    /**
     * 是否支持该格式
     */
    fun isSupport(url: String): Boolean

    /**
     * 解析数据
     */
    suspend fun parse(data: String): GitRelease

    companion object {
        val instances = listOf(
            GithubGitReleaseParser(),
            GiteeGitReleaseParser(),
        )

        /**
         * 选安装包附件：第一个 .apk 文件。原先直接取 assets[0]，
         * 发行版多一个附件（校验文件、第二种架构包）时就会把非安装包
         * 当 APK 下回来，大小校验还对得上（size 取自同一附件），
         * 最后在安装那一步才失败；没有 APK 就直接报错，别悄悄下错文件
         */
        fun selectApkAsset(assets: JsonArray): JsonObject {
            if (assets.isEmpty()) throw IllegalArgumentException("发行版没有附件")
            return assets.firstOrNull { asset ->
                asset.jsonObject["name"]?.jsonPrimitive?.content
                    ?.endsWith(".apk", ignoreCase = true) == true
            }?.jsonObject
                ?: throw IllegalArgumentException("发行版没有 APK 附件")
        }
    }
}