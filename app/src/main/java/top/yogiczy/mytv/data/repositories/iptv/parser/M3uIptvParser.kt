package top.yogiczy.mytv.data.repositories.iptv.parser

import top.yogiczy.mytv.data.entities.IptvGroupList

class M3uIptvParser : IptvParser {

    override fun isSupport(url: String, data: String): Boolean {
        // 部分编辑器（Windows 记事本等）保存的 M3U 带 UTF-8 BOM，或首行有
        // 空行：先去掉 BOM 与前导空白再判断，否则有效源会被判成不支持格式；
        // 有些生成器写小写 #extm3u，同样认
        return data.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U", ignoreCase = true)
    }

    override suspend fun parse(data: String): IptvGroupList {
        val lines = data.split("\r\n", "\n")
        val iptvList = mutableListOf<IptvParser.IptvResponseItem>()

        lines.forEachIndexed { index, line ->
            // 有些生成器/手改文件会缩进 #EXTINF 行或写小写 #extinf：
            // 先去前后空白再不区分大小写判断，否则整条频道被悄悄丢掉
            val extinfLine = line.trim()
            if (!extinfLine.startsWith("#EXTINF", ignoreCase = true)) return@forEachIndexed

            // 频道名是第一个不在引号内的逗号之后的内容：属性值里可能有
            // 逗号（如 tvg-logo 的 URL），直接 split(",").last() 会把带
            // 逗号的频道名截断成最后一截；没有这个逗号的残缺行（如生成器
            // 只写 `#EXTINF:-1`），原先把整行当频道名塞进列表，选台界面
            // 会出现一行 "#EXTINF:-1 ..." 的垃圾频道——没逗号时退到
            // tvg-name，有 tvg-name 频道还能播；两个都没有就跳过这条
            val tvgName = TVG_NAME_REGEX.find(extinfLine)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
            // tvg-id 和节目单 channel 的 id 是同一套：名字写法不同时
            // （直播源“CCTV综合”、节目单“CCTV-1 综合”），节目单按名
            // 查不到，靠它兜底；没有 tvg-id 的行记空串，走原来的按名查
            val tvgId = TVG_ID_REGEX.find(extinfLine)?.groupValues?.get(1)?.trim().orEmpty()
            val name = extinfLine.substringAfterFirstUnquotedComma()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: tvgName
                ?: return@forEachIndexed
            val channelName = tvgName ?: name
            val groupName = GROUP_TITLE_REGEX.find(extinfLine)?.groupValues?.get(1) ?: "其他"

            // 最后一行是 #EXTINF 且后面没有 URL 行时跳过（否则 lines[index + 1] 越界崩溃）
            val url = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (url.isEmpty() || url.startsWith("#")) return@forEachIndexed

            iptvList.add(
                IptvParser.IptvResponseItem(
                    name = name.trim(),
                    channelName = channelName.trim(),
                    groupName = groupName.trim(),
                    url = url,
                    tvgId = tvgId,
                )
            )
        }

        return IptvParser.buildGroupList(iptvList)
    }

    /**
     * 取第一个不在双引号内的逗号之后的内容；没有这种逗号时返回 null，
     * 调用方据此判断这行是不是残缺的 #EXTINF，而不是拿整行当频道名
     */
    private fun String.substringAfterFirstUnquotedComma(): String? {
        var inQuotes = false
        for (i in indices) {
            when (this[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return substring(i + 1)
            }
        }
        return null
    }

    companion object {
        // 正则只编译一次：原先每条 #EXTINF 行都 new 两个 Regex，大源很浪费。
        // 属性名不区分大小写：#EXTINF 本身已有生成器写小写（见 isSupport），
        // 同样有源把属性写成 TVG-NAME / Tvg-Name；区分大小写时 tvg-name、
        // tvg-id、group-title 全取不到，频道名退到逗号后、分组退到“其他”、
        // 节目单的 tvg-id 兜底也丢了
        private val TVG_NAME_REGEX = Regex("tvg-name=\"(.+?)\"", RegexOption.IGNORE_CASE)
        private val TVG_ID_REGEX = Regex("tvg-id=\"(.+?)\"", RegexOption.IGNORE_CASE)
        private val GROUP_TITLE_REGEX = Regex("group-title=\"(.+?)\"", RegexOption.IGNORE_CASE)
    }
}