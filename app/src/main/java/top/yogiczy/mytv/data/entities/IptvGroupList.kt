package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable

/**
 * 直播源分组列表
 */
@Immutable
data class IptvGroupList(
    val value: List<IptvGroup> = emptyList(),
) : List<IptvGroup> by value {
    /**
     * 全量频道列表：只摊平一次记下来。原来是扩展属性，每访问一次就把
     * 全部分组重新摊平成一份新列表；临时面板、经典面板、节目单刷新
     * 几处按需调用的地方每次都重新分配一份千级列表。现在跟节目单
     * EpgList 里那个按频道建表的做法一样，第一次访问时建好、之后
     * 直接拿同一份。分组列表对象是解析完就不再改的，记下来的这份
     * 不会变旧；copy 出新对象时会重新算，也不会串到旧的那份上。
     * 调用方都只读（筛、取名字），共用同一份没影响
     */
    val iptvList: List<Iptv> by lazy {
        value.flatMap { it.iptvList }
    }

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

        /**
         * 台号显示：频道在列表里时是下标+1、补零到两位；不在列表里
         * （空源的占位频道）显示“–”，不显示不存在的“00”。三处右上角
         * 台号（临时面板、普通面板、快捷面板）都调这一份。
         */
        fun IptvGroupList.channelNoText(iptv: Iptv): String {
            val idx = iptvIdx(iptv)
            return if (idx < 0) "–" else (idx + 1).toString().padStart(2, '0')
        }

        /**
         * 按全局下标取频道：按分组累加偏移逐组找，不用 flatMap 先把全量
         * 频道拼成一个新列表再取——上下键切台、输台号选台、启动恢复上次
         * 频道每次都调它，千级频道源下每次都分配一次全量列表；下标越界
         * （含负数）返回 null，由调用方决定兜底
         */
        fun IptvGroupList.iptvAt(globalIdx: Int): Iptv? {
            var offset = 0
            for (group in this) {
                if (globalIdx < offset + group.iptvList.size) {
                    return group.iptvList.getOrNull(globalIdx - offset)
                }
                offset += group.iptvList.size
            }
            return null
        }

        /**
         * 收藏频道列表：按收藏名单（频道名集合）从全量频道里筛，普通面
         * 板和经典面板原来各写了一份一模一样的，改一处容易漏另一处，
         * 现在两处都调这一份。同一台在两个分组里出现、名字地址都一样
         * 时，全量列表里是两个完全相同的频道对象——收藏夹直接拿这份
         * 去渲染会撞唯一键崩掉；去重后只留一个，本来就是同一条地址，
         * 播起来一模一样。名字相同、地址不同的台不受影响，各留各的。
         */
        fun IptvGroupList.favoriteIptvList(favoriteChannelNames: Collection<String>): IptvList {
            val favoriteSet = favoriteChannelNames.toSet()
            return IptvList(iptvList.filter { favoriteSet.contains(it.channelName) }.distinct())
        }
    }
}