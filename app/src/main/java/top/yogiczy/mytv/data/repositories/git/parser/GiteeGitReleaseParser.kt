package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.yogiczy.mytv.data.entities.GitRelease

class GiteeGitReleaseParser : GitReleaseParser {
    override fun isSupport(url: String): Boolean {
        return url.contains("gitee.com")
    }

    override suspend fun parse(data: String): GitRelease {
        val json = Json.parseToJsonElement(data).jsonObject
        val asset = json.getValue("assets").jsonArray[0].jsonObject

        return GitRelease(
            version = json.getValue("tag_name").jsonPrimitive.content.removePrefix("v"),
            downloadUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
            description = json.getValue("body").jsonPrimitive.content,
            // Gitee 的发行版信息不一定带 size，没有就记 -1（不校验）
            downloadSize = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
        )
    }
}