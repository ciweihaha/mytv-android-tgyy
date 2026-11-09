package top.yogiczy.mytv.data.entities

import kotlinx.serialization.Serializable

/**
 * 频道节目
 */
@Serializable
data class EpgProgramme(
    /**
     * 开始时间（时间戳）
     */
    val startAt: Long = 0,

    /**
     * 结束时间（时间戳）
     */
    val endAt: Long = 0,

    /**
     * 节目名称
     */
    val title: String = "",
) {
    companion object {
        /**
         * 是否正在直播
         */
        fun EpgProgramme.isLive() = System.currentTimeMillis() in startAt..<endAt

        /**
         * 节目进度：钳在 0~1 之间。退化节目（endAt <= startAt，如时间
         * 解析失败都记 0）返回 0；节目刚播完、重组时已越过 endAt 的
         * 返回 1，避免 Infinity/NaN 或超界值进 fillMaxWidth 搞坏布局
         */
        fun EpgProgramme.progress(): Float {
            if (endAt <= startAt) return 0f
            return ((System.currentTimeMillis() - startAt).toFloat() / (endAt - startAt))
                .coerceIn(0f, 1f)
        }
    }
}