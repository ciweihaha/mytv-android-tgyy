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
        val iptvList = mutableListOf<IptvResponseItem>()

        lines.forEachIndexed { index, line ->
            // 有些生成器/手改文件会缩进 #EXTINF 行或写小写 #extinf：
            // 先去前后空白再不区分大小写判断，否则整条频道被悄悄丢掉
            val extinfLine = line.trim()
            if (!extinfLine.startsWith("#EXTINF", ignoreCase = true)) return@forEachIndexed

            // 频道名是第一个不在引号内的逗号之后的内容：属性值里可能有
            // 逗号（如 tvg-logo 的 URL），直接 split(",").last() 会把带
            // 逗号的频道名截断成最后一截；没有这种逗号时保留整行当名字
            val name = extinfLine.substringAfterFirstUnquotedComma()
            val channelName = TVG_NAME_REGEX.find(extinfLine)?.groupValues?.get(1) ?: name
            val groupName = GROUP_TITLE_REGEX.find(extinfLine)?.groupValues?.get(1) ?: "其他"

            // 最后一行是 #EXTINF 且后面没有 URL 行时跳过（否则 lines[index + 1] 越界崩溃）
            val url = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (url.isEmpty() || url.startsWith("#")) return@forEachIndexed

            iptvList.add(
                IptvResponseItem(
                    name = name.trim(),
                    channelName = channelName.trim(),
                    groupName = groupName.trim(),
                    url = url,
                )
            )
        }

        return IptvParser.buildGroupList(iptvList)
    }

    /**
     * 取第一个不在双引号内的逗号之后的内容；没有则返回整行
     */
    private fun String.substringAfterFirstUnquotedComma(): String {
        var inQuotes = false
        for (i in indices) {
            when (this[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return substring(i + 1)
            }
        }
        return this
    }

    companion object {
        // 正则只编译一次：原先每条 #EXTINF 行都 new 两个 Regex，大源很浪费
        private val TVG_NAME_REGEX = Regex("tvg-name=\"(.+?)\"")
        private val GROUP_TITLE_REGEX = Regex("group-title=\"(.+?)\"")
    }
}