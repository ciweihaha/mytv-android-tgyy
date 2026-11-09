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
) {
    companion object {
        /**
         * 当前节目/下一个节目：一次 indexOfFirst 扫描同时拿到下标与节目。
         * 原先先 firstOrNull 找节目、再 indexOf（data class 全字段相等比较）
         * 二次扫描找下标——多天节目单下每个频道项每次组合都多扫一遍
         */
        fun Epg.currentProgrammes(): EpgProgrammeCurrent? {
            val index = programmes.indexOfFirst { it.isLive() }
            if (index < 0) return null

            return EpgProgrammeCurrent(
                now = programmes[index],
                next = programmes.getOrNull(index + 1),
            )
        }
    }
}