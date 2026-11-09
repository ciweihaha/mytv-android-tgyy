package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.yogiczy.mytv.data.entities.GitRelease
import top.yogiczy.mytv.data.utils.Constants

class GithubGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("github.com")
    }

    override suspend fun parse(data: String): GitRelease {
        val json = Json.parseToJsonElement(data).jsonObject
        val asset = GitReleaseParser.selectApkAsset(json.getValue("assets").jsonArray)

        return GitRelease(
            version = json.getValue("tag_name").jsonPrimitive.content.removePrefix("v"),
            downloadUrl = Constants.GITHUB_PROXY + (asset["browser_download_url"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("APK 附件没有下载地址")),
            // body 可为 JSON null（无更新说明的发行版）：contentOrNull 取不到
            // 时记空串，别把字面量 "null" 当说明显示
            description = json["body"]?.jsonPrimitive?.contentOrNull ?: "",
            downloadSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
        )
    }
}