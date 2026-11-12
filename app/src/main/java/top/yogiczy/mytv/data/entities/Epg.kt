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
         * 当前节目/下一个节目：单趟手动循环同时找正在播的节目（第一个
         * isLive）和还没开始的最早那档，全程零分配。原先三条
         * filter{}.minByOrNull{} 各扫一趟、每次都建中间列表；无正在播
         * 节目时现在一趟搞定，有正在播节目时第二趟只找“当前节目结束
         * 之后最早的那档”——它的条件依赖当前节目的 endAt，列表又不保
         * 证按时间排，当前节目之前的元素在找到它之前无法判定，所以那
         * 一支仍需第二趟；两趟都是手动循环，不建中间列表。minByOrNull
         * 平局取第一个，循环里用严格小于比较，平局语义保持一致。
         * 没有正在播的节目时也别直接回 null：节目空档、或当天首个节目
         * 还没开始时，面板里“正在播放”和“稍后播放”两行都会显示“无节
         * 目”，明明很快就有节目要播；这时把最近的下一个节目带上（正在
         * 播放仍是空）。节目单顺序不保证按时间排（XML 原样解析），按开
         * 始时间取最小的那个，不依赖列表顺序；所有节目都播完了才回 null
         * 正在播时也一样：下一个节目按时间取，不拿列表里正在播那条的
         * 下一条——顺序一乱，下邻居可能是已播完的、也可能是很久以后
         * 的。取开始时间在当前节目结束时刻之后、最早的那一档；都没
         * 有时再退一步，取还没开始的最早那档（节目时间有重叠的脏数
         * 据也能带上）
         */
        fun Epg.currentProgrammes(): EpgProgrammeCurrent? {
            val now = System.currentTimeMillis()
            var current: EpgProgramme? = null
            var earliestFuture: EpgProgramme? = null
            for (programme in programmes) {
                if (current == null && programme.isLive()) {
                    current = programme
                }
                if (programme.startAt > now &&
                    (earliestFuture == null || programme.startAt < earliestFuture.startAt)
                ) {
                    earliestFuture = programme
                }
            }
            if (current == null) {
                val next = earliestFuture ?: return null
                return EpgProgrammeCurrent(now = null, next = next)
            }

            var nextAfterEnd: EpgProgramme? = null
            for (programme in programmes) {
                if (programme.startAt >= current.endAt &&
                    (nextAfterEnd == null || programme.startAt < nextAfterEnd.startAt)
                ) {
                    nextAfterEnd = programme
                }
            }
            return EpgProgrammeCurrent(now = current, next = nextAfterEnd ?: earliestFuture)
        }
    }
}