package top.yogiczy.mytv.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.repositories.FileCacheRepository
import top.yogiczy.mytv.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.data.utils.HttpStatusException
import top.yogiczy.mytv.data.utils.buildGetRequest
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.utils.Logger
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 容错解码节目单缓存：缓存文件是 file.writeText 直接写入（非原子），
 * 进程在写入中途被杀会留下截断的 JSON；旧版本缓存的结构也可能与当前
 * Epg 对不上。解不开时返回 null，让调用方走刷新或给空节目单，而不
 * 是抛错——否则一次坏缓存会让节目单在缓存过期（隔天）前一直加载失败。
 * 顶层函数与 EpgRepository 对象无关，方便单元测试直接调用。
 */
internal fun decodeEpgListOrNull(json: String?): EpgList? {
    if (json.isNullOrBlank()) return null
    return try {
        EpgList(Json.decodeFromString<List<Epg>>(json))
    } catch (_: Exception) {
        null
    }
}

/**
 * 未到刷新时间点时能不能直接用缓存返回：缓存能解开就用它（源文件一般
 * 覆盖多天，凌晨时段的节目多半在里面）；没缓存（首次启动、清过缓存）
 * 或缓存解不开时返回 null，调用方不等时间点直接走刷新——原先没缓存
 * 直接给空节目单，0 点到阈值（默认 2 点）之间首次启动或清过缓存后
 * 一直显示“无节目”，要等阈值过了才恢复；拉一次比空着强。
 * 顶层函数方便单元测试直接调用。
 */
internal fun cachedEpgBeforeThreshold(cachedJson: String?): EpgList? =
    decodeEpgListOrNull(cachedJson)

/**
 * 节目时间能不能用：开始或结束解析失败都记 0（退化节目，progress()
 * 里也是这么叫的），结束早于等于开始的也是脏数据——这种节目永远
 * 播不了，还会坏三处显示：节目单弹窗里多一条“08:00 ~ 08:00”的
 * 假节目；开始时间在将来、结束是 0 的那条还可能被 currentProgrammes()
 * 当成“稍后播放”挑出来；经典面板按开始时间给节目分组，0 时间那条
 * 会多出一个 1970 年的日期栏，排在前面时还会当成默认选中的那天。
 * 解析时直接跳过，别进列表。顶层函数方便单元测试直接调用。
 */
internal fun isUsableEpgProgramme(programme: EpgProgramme): Boolean =
    programme.startAt > 0 && programme.endAt > programme.startAt

/**
 * 节目单时间的兜底解析：主解析只认 `yyyyMMddHHmmss Z`（`20261112080000 +0800`），
 * 但部分源的写法不一样——时区直接贴在秒后面（`20261112080000+0800`）、
 * 时区带冒号（`20261112080000 +08:00`，贴着秒的 `20261112080000+08:00` 也一样）、
 * 时区只写一个 `Z`（`20261112080000Z`，即 UTC）、或干脆不写时区（`20261112080000`），
 * 四种在主解析里全抛错记 0，整条节目 startAt/endAt 都是 0，当前节目永远
 * 查不到、面板一直显示“无节目”。按贴时区、冒号时区、贴着秒的冒号时区／`Z`、
 * 无时区（按本地时区，XMLTV 没写时区时就是本地时间）的顺序逐个试，全失败才记 0；仍是单条
 * 容错，不拖垮整个节目单。只在主解析失败时调，主路径仍复用那一个
 * SimpleDateFormat 实例。顶层函数方便单元测试直接调用。
 */
internal fun parseEpgTimeFallback(time: String): Long {
    val trimmed = time.trim()
    if (trimmed.length < 14) return 0
    // 贴时区/带空格的时区都能认；冒号时区（带空格与贴着秒两种）与贴着秒的 Z；最后是无时区的本地时间。
    // 注意顺序：无时区格式放最后——它会把 `20261112080000 +0800` 的
    // 前 14 位当本地时间解析、悄悄丢掉时区，带时区的写法必须先被认走。
    // 贴着秒的冒号时区（`20261112080000+08:00`）与 `Z`（`20261112080000Z`）也一样：
    // 前两档认不走（`Z` 不认冒号、带空格那档缺空格），无时区档会悄悄按本地时间解析，
    // 所以单独加一档贴着秒的 `XXX`（认 `+08:00` 与 `Z` 两种）夹在无时区档前面。
    val formats = listOf("yyyyMMddHHmmssZ", "yyyyMMddHHmmss XXX", "yyyyMMddHHmmssXXX", "yyyyMMddHHmmss")
    for (pattern in formats) {
        try {
            val parsed = SimpleDateFormat(pattern, Locale.getDefault()).parse(trimmed)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {
            // 换下一种写法再试
        }
    }
    return 0
}

/**
 * 节目单 XML 里一个频道要不要留：display-name 在直播源的频道名里，
 * 或 id 在直播源的 tvg-id 里。tvg-name 是纯 id（如 "cctv1"）的源，
 * 只按 display-name 比会把所有频道都滤掉、节目单全丢；id 对上也留，
 * 查节目单时 epgFor 还会再按名复核一次，不会贴错台。过滤集为空
 * （没传过滤条件）时全留，和原来一致。
 * 顶层函数方便单元测试直接调用。
 */
internal fun keepEpgChannel(
    channelId: String?,
    channelName: String?,
    filteredChannels: Set<String>,
): Boolean {
    if (channelId == null || channelName == null) return false
    if (filteredChannels.isEmpty()) return true
    return filteredChannels.contains(channelName) || filteredChannels.contains(channelId)
}

/**
 * 判断两个时间戳是否在同一天（默认时区）：拿年份＋年内第几天比，
 * 与按 "yyyy-MM-dd" 格式化后比字符串是同一个意思。
 * 这里不用 SimpleDateFormat：它不是线程安全的，而调用方跑在
 * Dispatchers.Default 线程池里，不能放成共用的；每次新建又白白
 * 造一个格式器。Calendar 每次现取现用，没有共享状态。
 */
private fun isSameDay(first: Long, second: Long): Boolean {
    val firstCal = Calendar.getInstance().apply { timeInMillis = first }
    val secondCal = Calendar.getInstance().apply { timeInMillis = second }
    return firstCal.get(Calendar.YEAR) == secondCal.get(Calendar.YEAR) &&
        firstCal.get(Calendar.DAY_OF_YEAR) == secondCal.get(Calendar.DAY_OF_YEAR)
}

/**
 * 节目单获取
 */
class EpgRepository : FileCacheRepository("epg.json") {
    private val log = Logger.create(javaClass.simpleName)
    private val epgXmlRepository = EpgXmlRepository()

    /**
     * 连同原始 XML 缓存一起清：节目单是两级缓存（epg.xml 原文 +
     * epg.json 解析结果），只清解析结果时，换源后新源拉取失败会回落
     * 到旧源的 epg.xml，把旧源的节目单当成新源的节目单显示、还写回
     * 缓存；FileCacheRepository 里“换源时已先清缓存、旧缓存只可能是
     * 同源”的前提在这里不成立。三处调用（设置页换源/清缓存、网页端
     * 改源）都是换源或清缓存语义，都要两级一起清
     */
    override fun clearCache() {
        super.clearCache()
        epgXmlRepository.clearCache()
    }

    /**
     * 解析节目单xml
     */
    private suspend fun parseFromXml(
        xmlString: String,
        filteredChannels: List<String> = emptyList(),
    ) = withContext(Dispatchers.Default) {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlString))

        // 一个 SimpleDateFormat 实例复用于全部节目（原先每个节目都新建一个，
        // 构造开销不小）；解析在单协程内顺序执行，无线程安全问题
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())

        fun parseTime(time: String?): Long {
            if (time == null || time.length < 14) return 0
            return try {
                timeFormat.parse(time)?.time ?: 0
            } catch (_: Exception) {
                // 单条时间格式异常时走兜底解析（贴时区/冒号时区/无时区），
                // 全失败才记 0，而不是让整个节目单解析失败
                parseEpgTimeFallback(time)
            }
        }

        // 频道名与节目列表分开累积：原先每次追加节目都用 `programmes + listOf(...)`
        // 复制整个列表，是 O(n²)；大节目单（多频道×多天）下很慢
        val channelNames = mutableMapOf<String, String>()
        val channelProgrammes = mutableMapOf<String, MutableList<EpgProgramme>>()
        // 过滤列表转 Set 再查：源频道上千时，List.contains 每个 channel
        // 都线性扫一遍，解析一次就是频道数×过滤数次字符串比较
        val filteredChannelSet = filteredChannels.toSet()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "channel") {
                        val channelId = parser.getAttributeValue(null, "id")
                        // 逐个扫子元素取第一个 display-name：自闭合 / 无
                        // display-name 的 channel 直接跳过，而不是让
                        // nextText() 抛错拖垮整个节目单
                        var channelName: String? = null
                        var innerEvent = parser.next()
                        while (innerEvent != XmlPullParser.END_DOCUMENT) {
                            if (innerEvent == XmlPullParser.END_TAG && parser.name == "channel") break
                            if (innerEvent == XmlPullParser.START_TAG &&
                                parser.name == "display-name" && channelName == null
                            ) {
                                // 含子元素的 display-name 会让 nextText() 抛错：
                                // 只跳过这个频道，别拖垮整个节目单
                                channelName = try {
                                    parser.nextText()
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            innerEvent = parser.next()
                        }

                        // 空值先判掉：keepEpgChannel 里也判空，但这里要把两个
                        // 值收窄成非空、后面才能当 Map 的键和值直接存
                        if (channelId != null && channelName != null &&
                            keepEpgChannel(channelId, channelName, filteredChannelSet)
                        ) {
                            channelNames[channelId] = channelName
                            // 与原先 `epgMap[id] = Epg(...)` 的语义一致：重复的 channel
                            // 标签会重置该频道的节目列表
                            channelProgrammes[channelId] = mutableListOf()
                        }
                    } else if (parser.name == "programme") {
                        val channelId = parser.getAttributeValue(null, "channel")
                        val startTime = parser.getAttributeValue(null, "start")
                        val stopTime = parser.getAttributeValue(null, "stop")
                        // 只取 <title> 子元素的文本：原先取"第一个子元素文本
                        // 当标题"，<desc> 在前时会把简介当标题；自闭合
                        // programme 则直接抛错拖垮整个节目单
                        var title: String? = null
                        var innerEvent = parser.next()
                        while (innerEvent != XmlPullParser.END_DOCUMENT) {
                            if (innerEvent == XmlPullParser.END_TAG && parser.name == "programme") break
                            if (innerEvent == XmlPullParser.START_TAG &&
                                parser.name == "title" && title == null
                            ) {
                                // 含子元素的 title 会让 nextText() 抛错：
                                // 只跳过这条节目，别拖垮整个节目单
                                title = try {
                                    parser.nextText()
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            innerEvent = parser.next()
                        }

                        if (title != null) {
                            val programme = EpgProgramme(
                                startAt = parseTime(startTime),
                                endAt = parseTime(stopTime),
                                title = title,
                            )
                            // 时间退化的节目（解析失败记 0、结束早于等于
                            // 开始）不进列表，见 isUsableEpgProgramme
                            if (isUsableEpgProgramme(programme)) {
                                channelProgrammes[channelId]?.add(programme)
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val epgList = EpgList(channelNames.map { (channelId, channelName) ->
            Epg(
                channelName,
                EpgProgrammeList(channelProgrammes[channelId] ?: emptyList()),
                // channel 标签的 id 原样存下来：和直播源的 tvg-id 是同一套，
                // 名字写法不同时节目单按 id 兜底查；没 id 的频道记空串
                id = channelId,
            )
        })

        log.i("解析节目单完成，共${epgList.size}个频道")
        return@withContext epgList
    }

    suspend fun getEpgList(
        xmlUrl: String,
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ) = withContext(Dispatchers.Default) {
        try {
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold) {
                // 未到时间点不拉取，但缓存的节目单仍可用（源文件一般覆盖
                // 多天，凌晨时段的节目多半在里面）：有缓存就返回缓存，
                // 别直接给空——原先每天 0 点到阈值（默认 2 点）之间完全
                // 没有节目信息。没缓存（首次启动、清过缓存）或缓存解不
                // 开时不等时间点、直接走下面的刷新：不拉就一直是空节目
                // 单，要等阈值过了才恢复；拉一次比空着强
                val cached = cachedEpgBeforeThreshold(getCachedData())
                if (cached != null) {
                    log.d("未到时间点，不刷新节目单，沿用缓存")
                    return@withContext cached
                }
                log.w("节目单缓存不可用，跳过等待直接刷新")
            }

            val xmlJson = getOrRefresh({ lastModified, _ ->
                !isSameDay(System.currentTimeMillis(), lastModified)
            }) {
                val xmlString = epgXmlRepository.getEpgXml(xmlUrl)
                Json.encodeToString(parseFromXml(xmlString, filteredChannels).value)
            }

            // 刷新刚成功时编解码必然能 round-trip；解不开说明拿到的是
            // getOrRefresh 刷新失败时顶上的旧坏缓存：给空节目单，别抛错
            decodeEpgListOrNull(xmlJson) ?: run {
                log.e("节目单数据无法解析，返回空节目单")
                EpgList()
            }
        } catch (ex: Exception) {
            log.e("获取节目单失败", ex)
            // 直接原样抛：再包一层 Exception(ex) 会让消息变成
            // "java.lang.Exception: 获取远程节目单xml失败，请检查网络连接"，
            // 和直播源那边（IptvRepository）的写法一致
            throw ex
        }
    }
}

/**
 * 节目单xml获取
 */
private class EpgXmlRepository : FileCacheRepository("epg.xml") {
    private val log = Logger.create(javaClass.simpleName)

    /**
     * 获取远程xml
     */
    private suspend fun fetchXml(url: String): String = withContext(Dispatchers.IO) {
        log.d("获取远程节目单xml: $url")

        val request = buildGetRequest(url, "节目单地址")

        try {
            // 响应体全路径都关：原来只有成功读流那条路关了 body（解码层
            // 的 .use），服务器出错（404/500）或没有解析器能认这个地址
            // 时直接抛错、响应体根本没关，连接回不到连接池；节目单每天
            // 定时拉、源一挂就反复失败几次，占满单 host 的连接槽后后续
            // 拉取一直等连接。成功路径也不受影响：解码层本来就关流，
            // 再关一次响应是安全的幂等操作。写法与 Downloader（第 180
            // 轮）与 QuickPanel 测速那处 .execute().use 保持一致
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpStatusException(
                        response.code,
                        "获取远程节目单xml失败：服务器返回 ${response.code}，请检查节目单地址"
                    )
                }

                val fetcher = EpgFetcher.instances.first { it.isSupport(url) }

                fetcher.fetch(response)
            }
        } catch (ex: Exception) {
            // 服务器回了明确状态码就原样抛：不是网络问题，别包成
            // “请检查网络连接”误导用户去查网络
            if (ex is HttpStatusException) throw ex
            throw Exception("获取远程节目单xml失败，请检查网络连接", ex)
        }
    }

    /**
     * 获取xml
     */
    suspend fun getEpgXml(url: String): String {
        return getOrRefresh(0) {
            fetchXml(url)
        }
    }
}
