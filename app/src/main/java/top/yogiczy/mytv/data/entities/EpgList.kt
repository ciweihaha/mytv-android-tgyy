package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes
import java.util.Locale

/**
 * 两个频道名是不是同一个台：先把大小写、数字、空白、“-”“+”去掉再比——
 * 直播源里写“CCTV综合”、节目单里写“CCTV-1 综合”，数字和空格只是
 * 写法差别。只在按 tvg-id 对上号之后才用它复核一次：上游把海南卫视的
 * id 写成了湖南卫视备的 id（hnws2），只按 id 认会把湖南卫视备的节目单
 * 贴到海南卫视上；名字对不上就宁可不贴（和原来一样显示无节目），也不
 * 贴错台。归一化后是空串（如名字只剩数字）不算相同，避免“1”和“2”撞到一起。
 * 数字串也要对得上：只去数字比时“CCTV-4K 超高清”和“CCTV-8K 超高清”
 * 都变成“cctvk超高清”，会被当成同一个台——上游一旦把 4K 的 id 标成
 * 8K（海南卫视的 id 被标错就有先例），复核就拦不住、错台的节目单直接
 * 贴过去。数字串相等、或一边没有数字（“CCTV综合”对“CCTV-1 综合”）
 * 才算对上；两边都有数字且不一样（如 4 对 8）就按不同台处理。
 * 顶层函数方便单元测试直接调用。
 */
internal fun isSameEpgChannelName(m3uName: String, epgName: String): Boolean {
    fun normalized(name: String): String =
        name.lowercase(Locale.ROOT)
            .filterNot { it.isDigit() || it.isWhitespace() || it == '-' || it == '+' }
    val first = normalized(m3uName)
    val second = normalized(epgName)
    if (first.isEmpty() || first != second) return false
    val firstDigits = m3uName.filter { it.isDigit() }
    val secondDigits = epgName.filter { it.isDigit() }
    return firstDigits.isEmpty() || secondDigits.isEmpty() || firstDigits == secondDigits
}

@Immutable
data class EpgList(
    val value: List<Epg> = emptyList(),
) : List<Epg> by value {
    /**
     * 按频道名建好的表：节目单进来只建一次，之后按名直接查；
     * 原先每次取当前频道节目都拿频道名把整张表扫一遍，主界面
     * 临时面板、普通面板、快捷面板三处都走这里。表只在新节目
     * 单对象进来时才建，平时不重建。
     * 同名频道（两个源都叫一个名）留第一次出现的，跟原来
     * firstOrNull 取到的那条一致。
     * 四个面板（普通/收藏/经典频道列表、经典面板）原来各建一份
     * 一模一样的表，现在都直接用这一份，同一份节目单只建一次。
     */
    val byChannel: Map<String, Epg> by lazy {
        buildMap {
            value.forEach { putIfAbsent(it.channel, it) }
        }
    }

    /**
     * 按频道 id 建好的表：和按名那份一样，节目单进来只建一次。直播源
     * 的 tvg-id 和节目单 channel 的 id 是同一套（默认源 50 个带
     * tvg-id 的台全部能在节目单里按 id 对上），名字写法不同时靠它兜底；
     * 没 id 的条目不进表。同名频道（两个源都叫一个名）留第一次出现的，
     * 跟按名那份一致。
     */
    val byChannelId: Map<String, Epg> by lazy {
        buildMap {
            value.forEach { if (it.id.isNotBlank()) putIfAbsent(it.id, it) }
        }
    }

    /**
     * 取某频道的节目单：先按名查（原来的做法，名字对得上的台走这里，
     * 行为和原来一字不变）；名字查不到时再按 tvg-id 查——默认源里 19 个
     * 台名字写法和节目单不一样（直播源“CCTV综合”、节目单“CCTV-1 综合”），
     * 原来全显示“无节目”，按 id 能找回它们的节目单；id 对上后还要名字
     * 复核通过才认（见 isSameEpgChannelName），上游 id 标错的台不贴错节目单。
     * 复核拿显示名也算一次：tvg-name 是纯 id（如 "cctv1"）的源，拿它去
     * 对节目单名永远对不上、id 兜底等于没开；显示名（"CCTV-1 综合"）和
     * 节目单名是同一套叫法，对得上才是同一台。复核本身仍是严格相等
     * （isSameEpgChannelName 一字不动），海南/湖南、4K/8K 的拦法还在。
     */
    fun epgFor(iptv: Iptv): Epg? {
        byChannel[iptv.channelName]?.let { return it }
        if (iptv.tvgId.isBlank()) return null
        val byId = byChannelId[iptv.tvgId] ?: return null
        return byId.takeIf {
            isSameEpgChannelName(iptv.channelName, it.channel) ||
                isSameEpgChannelName(iptv.name, it.channel)
        }
    }

    /**
     * 当前节目/下一个节目
     */
    fun currentProgrammes(iptv: Iptv): EpgProgrammeCurrent? {
        return epgFor(iptv)?.currentProgrammes()
    }
}
