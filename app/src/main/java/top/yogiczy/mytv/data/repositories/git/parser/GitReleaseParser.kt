package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
         * 按更新源地址选解析器：GitHub / Gitee 之外返回 null，调用方给明确
         * 报错，别让 first{} 抛 NoSuchElementException 再被包成"请检查
         * 网络连接"误导（网络没问题，只是不支持这个更新源）。
         */
        fun selectParser(url: String): GitReleaseParser? =
            instances.firstOrNull { it.isSupport(url) }

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

        /**
         * GitHub / Gitee 发行版信息的公共解析：两边字段完全一样
         * （tag_name、body、assets），原先各写一份，改一处容易漏
         * 另一处。唯一差别是下载地址前缀：GitHub 走代理前缀，
         * Gitee 直接用附件地址，前缀默认空串。
         */
        fun parseRelease(data: String, downloadUrlPrefix: String = ""): GitRelease {
            val json = Json.parseToJsonElement(data).jsonObject
            val asset = selectApkAsset(json.getValue("assets").jsonArray)

            return GitRelease(
                // tag 前缀按“第一个数字之前全去掉”：上游最新 release 的
                // tag 是 "tv2.2.7"（旧版还有大写 V 的 "V3.0.0.238"），只去
                // 小写 v 会把 "tv2.2.7" 原样留成版本号，三处显示（更新弹窗、
                // 应用设置页、提示条）又各拼一个 v，最后显示成
                // "vtv2.2.7"；和 compareVersion 里去前缀是同一做法，版本
                // 比较那边的行为不变
                version = json.getValue("tag_name").jsonPrimitive.content.trim()
                    .trimStart { !it.isDigit() },
                // browser_download_url 为 JSON null 时不能用 content：
                // JsonNull 也是 JsonPrimitive，content 是字面量 "null"，
                // 拼出来的下载地址会是 "https://mirror.ghproxy.com/null"，
                // 下载失败时只报网络错、根因很难查；contentOrNull 在
                // null 时给 null，走下面预期的"没有下载地址"报错（和
                // body 那行的写法一致）
                downloadUrl = downloadUrlPrefix + (asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("APK 附件没有下载地址")),
                // body 可为 JSON null（无更新说明的发行版）：contentOrNull 取不到
                // 时记空串，别把字面量 "null" 当说明显示
                description = json["body"]?.jsonPrimitive?.contentOrNull ?: "",
                // 发行版信息不一定带 size，没有就记 -1（下载时不校验）
                downloadSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
            )
        }
    }
}
