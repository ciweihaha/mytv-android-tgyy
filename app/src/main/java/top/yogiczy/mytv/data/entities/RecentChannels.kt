package top.yogiczy.mytv.data.entities

/**
 * 最近观看的频道名列表（最近看过的在最前面）：切台时记一次，面板里
 * “最近观看”按这份顺序取频道。纯函数，不碰 SP，方便单元测试直接调用；
 * 存盘与读写在 SP.iptvRecentChannelList，面板读取走 SettingsViewModel。
 */
const val MAX_RECENT_CHANNELS = 10

/**
 * 记一次观看：刚看过的提到最前面、同名旧记录去掉、只留前 max 条。
 * 空白频道名不记（占位频道 Iptv() 的 channelName 是空串，启动恢复
 * 的空源占位台不该进最近列表）；max 小于 1 时直接给空列表。
 */
fun recordRecentChannel(
    recentChannelNames: List<String>,
    channelName: String,
    max: Int = MAX_RECENT_CHANNELS,
): List<String> {
    if (max < 1) return emptyList()
    if (channelName.isBlank()) return recentChannelNames.take(max)
    return (listOf(channelName) + recentChannelNames.filter { it != channelName }).take(max)
}

/**
 * 最近名单的存盘编码：SharedPreferences 的 StringSet 不保序，最近
 * 列表的顺序就是价值，必须用单个字符串存；频道名里不会出现换行，
 * 用换行分隔、空行丢弃，解码失败（空串）时给空列表。
 */
fun encodeRecentChannels(recentChannelNames: List<String>): String =
    recentChannelNames.filter { it.isNotBlank() }.joinToString("\n")

fun decodeRecentChannels(encoded: String?): List<String> {
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
