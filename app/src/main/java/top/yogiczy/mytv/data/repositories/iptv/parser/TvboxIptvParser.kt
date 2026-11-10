package top.yogiczy.mytv.data.repositories.iptv.parser

import top.yogiczy.mytv.data.entities.IptvGroupList

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

            // 先统一中文逗号：分组行 `央视，#genre#` 也要认
            val normalized = line.replace("，", ",")
            // 分组行是 `分组名,#genre#`：只看第一个逗号后面的段是否以
            // #genre# 开头；频道地址里含 #genre#（如 http://x/a#genre#）
            // 时整行是频道，不能当分组行——否则频道被丢掉，分组名还会被
            // 写成频道名
            val firstComma = normalized.indexOf(',')
            if (firstComma >= 0 &&
                normalized.substring(firstComma + 1).trimStart().startsWith("#genre#")
            ) {
                // 只有带分组名的才更新：",#genre#" 这种无名行不能把分组名
                // 写成空串，后面的频道会进一个无名假分组
                normalized.substring(0, firstComma).trim()
                    .takeIf { it.isNotEmpty() && it != "#genre#" }
                    ?.let { groupName = it }
            } else {
                val res = normalized.split(",")
                if (res.size < 2) return@forEach

                // 空地址段直接丢弃：`CCTV-1,` 或 `CCTV-1,http://a#` 这种行
                // 原先会把空字符串塞进 urlList，播放时白白走一次错误重试
                // 地址里的 `#` 既是多源分隔符、也可能是 URL 片段：
                // `http://x/a#genre#` 按 `#` 切开会多出 "genre" 这种不
                // 是地址的段；只留带 scheme 的段。全部段都不带 scheme 时
                // 保留原样，别把频道整个丢掉（无 scheme 的地址本身也许播
                // 不了，但那是播放器的事，不该由解析器悄悄丢频道）
                val urls = res[1].split("#").map { it.trim() }.filter { it.isNotEmpty() }
                    .let { segments -> segments.filter { it.contains("://") }.ifEmpty { segments } }
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

        return IptvParser.buildGroupList(iptvList)
    }
}
