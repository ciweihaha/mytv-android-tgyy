package top.yogiczy.mytv.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.repositories.FileCacheRepository
import top.yogiczy.mytv.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.utils.Logger
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
                log.d("未到时间点，不刷新节目单，沿用缓存")
                val cachedJson = getCachedData()
                return@withContext if (cachedJson.isNullOrBlank()) EpgList()
                else EpgList(Json.decodeFromString<List<Epg>>(cachedJson))
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val xmlJson = getOrRefresh({ lastModified, _ ->
                dateFormat.format(System.currentTimeMillis()) != dateFormat.format(lastModified)
            }) {
                val xmlString = epgXmlRepository.getEpgXml(xmlUrl)
                Json.encodeToString(parseFromXml(xmlString, filteredChannels).value)
            }

            EpgList(Json.decodeFromString<List<Epg>>(xmlJson))
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

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        try {
            with(client.newCall(request).execute()) {
                if (!isSuccessful) {
                    throw Exception("获取远程节目单xml失败: $code")
                }

                val fetcher = EpgFetcher.instances.first { it.isSupport(url) }

                return@with fetcher.fetch(this)
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
