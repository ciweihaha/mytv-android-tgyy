package top.yogiczy.mytv.data.entities

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.isLive

/**
 * 频道节目单
 */
@Serializable
data class Epg(
    /**
     * 频道名称
     */
    val channel: String = "",

    /**
     * 节目列表
     */
    val programmes: EpgProgrammeList = EpgProgrammeList(),

    /**
     * 节目单里的频道 id（xml 里 channel 标签的 id 属性）：和直播源
     * 的 tvg-id 是同一套，名字写法不同时靠它兜底查节目单。旧缓存里
     * 没有这个字段，解码时按默认值空串走，不会读坏老缓存
     */
    val id: String = "",
) {
    companion object {
        /**
         * 当前节目/下一个节目：一次 indexOfFirst 扫描同时拿到下标与节目。
         * 原先先 firstOrNull 找节目、再 indexOf（data class 全字段相等比较）
         * 二次扫描找下标——多天节目单下每个频道项每次组合都多扫一遍
         * 没有正在播的节目时也别直接回 null：节目空档、或当天首个节目
         * 还没开始时，面板里“正在播放”和“稍后播放”两行都会显示“无节
         * 目”，明明很快就有节目要播；这时把最近的下一个节目带上（正在
         * 播放仍是空）。节目单顺序不保证按时间排（XML 原样解析），按开
         * 始时间取最小的那个，不依赖列表顺序；所有节目都播完了才回 null
         */
        fun Epg.currentProgrammes(): EpgProgrammeCurrent? {
            val now = System.currentTimeMillis()
            val index = programmes.indexOfFirst { it.isLive() }
            if (index >= 0) {
                return EpgProgrammeCurrent(
                    now = programmes[index],
                    next = programmes.getOrNull(index + 1),
                )
            }

            val next = programmes.filter { it.startAt > now }.minByOrNull { it.startAt }
                ?: return null
            return EpgProgrammeCurrent(now = null, next = next)
        }
    }
}