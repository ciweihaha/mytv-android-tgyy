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

/**
 * 节目单列表的唯一键：开始、结束、标题都一样的重复节目（节目单源里
 * 同一条节目被写了两遍）只按内容设键会撞键、列表直接崩掉；下标是
 * 列表里的位置，同一份列表里不会重复。普通面板和经典面板的节目单
 * 列表都用这一份；两处列表状态都在节目单变了时重建，下标键在同一
 * 轮列表里是稳定的。
 * 顶层函数方便单元测试直接调用。
 */
internal fun epgProgrammeKey(index: Int, programme: EpgProgramme): String =
    "$index-${programme.startAt}-${programme.endAt}-${programme.title}"