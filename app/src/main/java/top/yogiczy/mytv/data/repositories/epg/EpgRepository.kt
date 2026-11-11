package top.yogiczy.mytv.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.repositories.FileCacheRepository
import top.yogiczy.mytv.data.repositories.epg.fetcher.EpgFetcher
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
                // 单条时间格式异常时记 0，而不是让整个节目单解析失败
                0
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

                        if (channelId != null && channelName != null &&
                            (filteredChannelSet.isEmpty() || filteredChannelSet.contains(channelName))
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
                            channelProgrammes[channelId]?.add(
                                EpgProgramme(
                                    startAt = parseTime(startTime),
                                    endAt = parseTime(stopTime),
                                    title = title,
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val epgList = EpgList(channelNames.map { (channelId, channelName) ->
            Epg(channelName, EpgProgrammeList(channelProgrammes[channelId] ?: emptyList()))
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
                // 没有节目信息
                val cachedJson = getCachedData()
                if (cachedJson.isNullOrBlank()) return@withContext EpgList()
                val cached = decodeEpgListOrNull(cachedJson)
                if (cached != null) {
                    log.d("未到时间点，不刷新节目单，沿用缓存")
                    return@withContext cached
                }
                // 缓存损坏或与当前结构对不上：不等时间点，直接走下面的刷新
                log.w("节目单缓存无法解析，跳过等待直接刷新")
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
            throw Exception(ex)
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

        val request = Request.Builder().url(url).build()

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
                    throw Exception("获取远程节目单xml失败: ${response.code}")
                }

                val fetcher = EpgFetcher.instances.first { it.isSupport(url) }

                fetcher.fetch(response)
            }
        } catch (ex: Exception) {
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
