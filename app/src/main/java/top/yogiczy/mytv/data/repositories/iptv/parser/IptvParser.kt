package top.yogiczy.mytv.data.repositories.iptv.parser

import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

/**
 * 直播源数据解析接口
 */
interface IptvParser {
    /**
     * 是否支持该直播源格式
     */
    fun isSupport(url: String, data: String): Boolean

    /**
     * 解析直播源数据
     */
    suspend fun parse(data: String): IptvGroupList

    /**
     * 解析出来的单条频道：m3u 与 tvbox 两份解析器原来各复制了一份
     * 一模一样的中间结构，改一处容易漏另一处，抽到这里共用
     */
    data class IptvResponseItem(
        val name: String,
        val channelName: String,
        val groupName: String,
        val url: String,
    )

    companion object {
        val instances = listOf(
            M3uIptvParser(),
            TvboxIptvParser(),
            DefaultIptvParser(),
        )

        /**
         * 把平铺的频道按分组、再按频道名拼成分组列表：同名频道的多条
         * 地址并进同一个频道的 urlList；两份解析器原来各复制了一份
         * 一模一样的拼装代码，抽到这里共用，行为不变
         */
        fun buildGroupList(items: List<IptvResponseItem>): IptvGroupList {
            return IptvGroupList(items.groupBy { it.groupName }.map { groupEntry ->
                IptvGroup(
                    name = groupEntry.key,
                    iptvList = IptvList(groupEntry.value.groupBy { it.name }.map { nameEntry ->
                        Iptv(
                            name = nameEntry.key,
                            channelName = nameEntry.value.first().channelName,
                            urlList = nameEntry.value.map { it.url },
                        )
                    })
                )
            })
        }
    }
}
