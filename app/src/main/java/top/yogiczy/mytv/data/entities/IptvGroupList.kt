package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable

/**
 * 直播源分组列表
 */
@Immutable
data class IptvGroupList(
    val value: List<IptvGroup> = emptyList(),
) : List<IptvGroup> by value {
    companion object {
        val EXAMPLE = IptvGroupList(List(5) { groupIdx ->
            IptvGroup(
                name = "频道分组${groupIdx + 1}",
                iptvList = IptvList(
                    List(10) { idx ->
                        Iptv(
                            name = "频道${groupIdx + 1}-${idx + 1}",
                            channelName = "频道${groupIdx + 1}-${idx + 1}",
                            urlList = emptyList(),
                        )
                    },
                )
            )
        })

        fun IptvGroupList.iptvGroupIdx(iptv: Iptv) =
            this.indexOfFirst { group -> group.iptvList.any { it == iptv } }

        /**
         * 频道在全部频道中的全局下标：按分组累加偏移逐组找，不用 flatMap
         * 先把全量频道拼成一个新列表再扫——临时面板/快捷面板每次组合
         * 都调它（算频道号），千级频道源下每次都分配一次全量列表
         */
        fun IptvGroupList.iptvIdx(iptv: Iptv): Int {
            var offset = 0
            for (group in this) {
                val index = group.iptvList.indexOfFirst { it == iptv }
                if (index >= 0) return offset + index
                offset += group.iptvList.size
            }
            return -1
        }

        val IptvGroupList.iptvList: List<Iptv>
            get() = this.flatMap { it.iptvList }
    }
}