package top.yogiczy.mytv.data.repositories.iptv.parser

import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

class TvboxIptvParser : IptvParser {

    override fun isSupport(url: String, data: String): Boolean {
        return data.contains("#genre#")
    }

    override suspend fun parse(data: String): IptvGroupList {
        val lines = data.split("\r\n", "\n")
        val iptvList = mutableListOf<IptvResponseItem>()

        var groupName: String? = null
        lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach

            if (line.contains("#genre#")) {
                // 只有带分组名的才更新：",#genre#" 这种无名行不能把分组名
                // 写成空串，后面的频道会进一个无名假分组
                line.split(",").first().trim()
                    .takeIf { it.isNotEmpty() && it != "#genre#" }
                    ?.let { groupName = it }
            } else {
                val res = line.replace("，", ",").split(",")
                if (res.size < 2) return@forEach

                // 空地址段直接丢弃：`CCTV-1,` 或 `CCTV-1,http://a#` 这种行
                // 原先会把空字符串塞进 urlList，播放时白白走一次错误重试
                val urls = res[1].split("#").map { it.trim() }.filter { it.isNotEmpty() }
                if (urls.isEmpty()) return@forEach

                iptvList.addAll(urls.map { url ->
                    IptvResponseItem(
                        name = res[0].trim(),
                        channelName = res[0].trim(),
                        groupName = groupName?.trim() ?: "其他",
                        url = url,
                    )
                })
            }
        }

        return IptvGroupList(iptvList.groupBy { it.groupName }.map { groupEntry ->
            IptvGroup(
                name = groupEntry.key,
                iptvList = IptvList(groupEntry.value.groupBy { it.name }.map { nameEntry ->
                    Iptv(
                        name = nameEntry.key,
                        channelName = nameEntry.value.first().channelName,
                        urlList = nameEntry.value.map { it.url },
                    )
                }),
            )
        })
    }

    private data class IptvResponseItem(
        val name: String,
        val channelName: String,
        val groupName: String,
        val url: String,
    )
}