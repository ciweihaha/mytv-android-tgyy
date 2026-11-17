package top.yogiczy.mytv.data.entities

import androidx.compose.runtime.Immutable

/**
 * 频道名/查询词归一化：去大小写、空白、“-”“+”再比——直播源里写
 * “CCTV-1 综合”、用户搜 “cctv1” 也要命中；数字保留不去掉，
 * “cctv1” 和 “cctv2” 是不同的台（和节目单复核 isSameEpgChannelName
 * 去数字那套不同，搜索不能把 1 台和 2 台搜成同一个）。
 * 顶层函数方便单元测试直接调用。
 */
internal fun normalizeChannelText(text: String): String =
    text.lowercase(java.util.Locale.ROOT)
        .filterNot { it.isWhitespace() || it == '-' || it == '+' }

/**
 * 一条频道匹不匹配搜索词：名字、频道名、tvg-id 任一归一化后包含
 * 查询词就算命中；查询词本身也先归一化。空白查询词不匹配任何台，
 * 调用方直接给空列表，别把全量频道当搜索结果返回。
 * 顶层函数方便单元测试直接调用。
 */
internal fun matchesChannelQuery(iptv: Iptv, normalizedQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return false
    return normalizeChannelText(iptv.name).contains(normalizedQuery) ||
        normalizeChannelText(iptv.channelName).contains(normalizedQuery) ||
        normalizeChannelText(iptv.tvgId).contains(normalizedQuery)
}

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

    /**
     * 频道 → 全局下标的表：iptvIdx 原先每次都按分组逐组线性扫（每次
     * 比较都是 Iptv 整对象相等、还要比 urlList），上下键连切、台号
     * 显示、最近观看几处每步都扫一遍，千级频道源下每次 O(n)；现在
     * 第一次查时建一次表、之后 O(1) 直查。同一个频道对象在两个分组
     * 里出现时留第一次出现的下标，和原来 indexOfFirst 取到的那条一致。
     * 分组列表对象解析完就不再改，建好的表不会变旧。
     */
    val iptvIndexMap: Map<Iptv, Int> by lazy {
        buildMap { iptvList.forEachIndexed { idx, iptv -> putIfAbsent(iptv, idx) } }
    }

    /**
     * 频道 → 分组下标的表：和上面那份一样，第一次查时建一次、
     * 之后 O(1) 直查；原来 iptvGroupIdx 每次都逐组 any 扫一遍。
     * 同一频道在两个分组里出现时留第一次出现的分组，和原来一致。
     */
    val iptvGroupIndexMap: Map<Iptv, Int> by lazy {
        buildMap {
            forEachIndexed { groupIdx, group ->
                group.iptvList.forEach { putIfAbsent(it, groupIdx) }
            }
        }
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
            iptvGroupIndexMap[iptv] ?: -1

        /**
         * 频道在全部频道中的全局下标：原先按分组累加偏移逐组找，每次
         * 都拿 Iptv 整对象比一遍；现在查预建好的索引表，O(1)。
         * 找不到回 -1，语义和原来一字不变。
         */
        fun IptvGroupList.iptvIdx(iptv: Iptv): Int =
            iptvIndexMap[iptv] ?: -1

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

        /**
         * 频道搜索：按名字、频道名、tvg-id 任一归一化后包含查询词就算
         * 命中（见 matchesChannelQuery）。结果按源里分组顺序、同名
         * 不同地址的台各留各的（和收藏夹那份去重逻辑一样，只去重完全
         * 相同的频道对象）；空白查询词给空列表，不返回全量频道。
         * 面板搜索入口直接调这一份，遥控器文本输入框在平板回来后接。
         */
        fun IptvGroupList.searchIptv(query: String): IptvList {
            val normalizedQuery = normalizeChannelText(query)
            if (normalizedQuery.isEmpty()) return IptvList()
            return IptvList(iptvList.filter { matchesChannelQuery(it, normalizedQuery) }.distinct())
        }

        /**
         * 最近观看的频道列表：按最近名单（最近看过的在最前）的顺序取，
         * 每个名字只取第一次出现的频道（同一台在两个分组里出现时只留
         * 一个，和收藏夹一致）；名单里已不在源里的名字直接跳过，不占位。
         */
        fun IptvGroupList.recentIptvList(recentChannelNames: Collection<String>): IptvList {
            if (recentChannelNames.isEmpty()) return IptvList()
            val byChannelName = buildMap<String, Iptv> {
                iptvList.forEach { putIfAbsent(it.channelName, it) }
            }
            return IptvList(
                recentChannelNames.mapNotNull { byChannelName[it] }.distinct()
            )
        }
    }
}