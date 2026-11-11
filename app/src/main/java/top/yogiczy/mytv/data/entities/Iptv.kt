package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable

/**
 * 直播源
 */
@Immutable
data class Iptv(
    /**
     * 直播源名称
     */
    val name: String = "",

    /**
     * 频道名称，用于查询节目单
     */
    val channelName: String = "",

    /**
     * 节目单里的频道 id（m3u 的 tvg-id）：直播源里的名字和节目单里的
     * 名字写法常不一样（直播源“CCTV综合”、节目单“CCTV-1 综合”），
     * 只按名查节目单会查不到；id 是两边同一套，查不到时靠它兜底。
     * 没有 tvg-id 的源记空串，走原来的按名查。收藏仍按 channelName
     * 存，这里不动，不会丢老用户的收藏
     */
    val tvgId: String = "",

    /**
     * 播放地址
     */
    val urlList: List<String> = emptyList(),
) {
    companion object {
        val EXAMPLE = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf(
                "http://dbiptv.sn.chinamobile.com/PLTV/88888890/224/3221226231/index.m3u8",
                "http://[2409:8087:5e01:34::20]:6610/ZTE_CMS/00000001000000060000000000000131/index.m3u8?IAS",
            ),
        )
    }
}