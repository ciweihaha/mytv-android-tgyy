package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable
import top.yogiczy.mytv.data.entities.Epg.Companion.currentProgrammes

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
     */
    private val byChannel: Map<String, Epg> by lazy {
        buildMap {
            value.forEach { putIfAbsent(it.channel, it) }
        }
    }

    /**
     * 当前节目/下一个节目
     */
    fun currentProgrammes(iptv: Iptv): EpgProgrammeCurrent? {
        return byChannel[iptv.channelName]?.currentProgrammes()
    }
}
