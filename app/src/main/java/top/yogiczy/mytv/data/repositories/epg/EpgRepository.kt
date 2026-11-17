package top.yogiczy.mytv.data.repositories.epg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import top.yogiczy.mytv.data.entities.Epg
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgProgramme
import top.yogiczy.mytv.data.entities.EpgProgrammeList
import top.yogiczy.mytv.data.repositories.FileCacheRepository
import top.yogiczy.mytv.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.data.utils.HttpStatusException
import top.yogiczy.mytv.data.utils.buildGetRequest
import top.yogiczy.mytv.data.utils.sharedHttpClient
import top.yogiczy.mytv.utils.Logger
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 容错解码节目单缓存：正常路径已是原子写（临时文件＋改名，见
 * FileCacheRepository.setCacheData），但截断的 JSON 仍可能出现——
 * 原子写之前的旧缓存还留在用户机器上，改名失败回退直写那条路
 * 写一半被杀也会截断；旧版本缓存的结构也可能与当前 Epg 对不上。
 * 解不开时返回 null，让调用方走刷新或给空节目单，而不是抛错——
 * 否则一次坏缓存会让节目单在缓存过期（隔天）前一直加载失败。
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
 * 缓存里有没有正在播的节目：只看文件修改日期是不是今天会中招——
 * 凌晨 00:00–05:32 拉到的还是服务端昨天的数据（早上 05:32 才合
 * 并出今天的），带着今天的时间戳存下来，白天节目全播完了、
 * currentProgrammes() 找不到正在播的，节目单整天显示无节目；
 * 只按日期判会一直沿用这份缓存到明天。任一台有一档正在播才算
 * 内容还新鲜，否则不管文件日期都当过期、重新拉取。时间范围与
 * EpgProgramme.isLive() 一致（开始 <= 现在 < 结束），这里直接
 * 按 now 参数比，方便单元测试用固定时间断言。
 * 顶层函数方便单元测试直接调用。
 */
internal fun hasLiveProgramme(
    epgList: EpgList?,
    now: Long = System.currentTimeMillis(),
): Boolean {
    if (epgList == null) return false
    return epgList.any { epg ->
        epg.programmes.any { it.startAt <= now && now < it.endAt }
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
 * 主源与备用源二选一：主源没有正在播的节目、备用源有，就用备用源；
 * 其余情况（主源新鲜、两边都没有正在播的）都沿用主源——备用源也
 * 过期时换过去没有意义，主源至少是用户自己的源、面板还会回落到
 * 有节目的第一天。顶层函数方便单元测试直接调用。
 */
internal fun selectEpgList(
    primary: EpgList,
    backup: EpgList,
    now: Long = System.currentTimeMillis(),
): EpgList =
    if (!hasLiveProgramme(primary, now) && hasLiveProgramme(backup, now)) backup else primary

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
 * SimpleDateFormat 实例。
 * 四个格式器按线程各存一份复用：SimpleDateFormat 不是线程安全的、不能
 * 跨线程共用，但每次失败都现建 4 个也很浪费——坏时间格式的源每条节目
 * 都要走 4 次构造；ThreadLocal 让同一线程（解析跑在单个协程里）复用同一份。
 * 顶层函数方便单元测试直接调用。
 */
private val epgTimeFallbackFormats: ThreadLocal<List<SimpleDateFormat>> =
    ThreadLocal.withInitial {
        listOf("yyyyMMddHHmmssZ", "yyyyMMddHHmmss XXX", "yyyyMMddHHmmssXXX", "yyyyMMddHHmmss")
            .map { pattern ->
                SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
            }
    }

internal fun parseEpgTimeFallback(time: String): Long {
    val trimmed = time.trim()
    if (trimmed.length < 14) return 0
    // 贴时区/带空格的时区都能认；冒号时区（带空格与贴着秒两种）与贴着秒的 Z；最后是无时区的本地时间。
    // 注意顺序：无时区格式放最后——它会把 `20261112080000 +0800` 的
    // 前 14 位当本地时间解析、悄悄丢掉时区，带时区的写法必须先被认走。
    // 贴着秒的冒号时区（`20261112080000+08:00`）与 `Z`（`20261112080000Z`）也一样：
    // 前两档认不走（`Z` 不认冒号、带空格那档缺空格），无时区档会悄悄按本地时间解析，
    // 所以单独加一档贴着秒的 `XXX`（认 `+08:00` 与 `Z` 两种）夹在无时区档前面。
    // 严格模式：SimpleDateFormat 默认宽容模式会把非法日期顺延
    // 成有效日期（2 月 30 日算成 3 月 2 日、13 月算成次年 1 月、
    // 24 点算成次日 0 点），节目会出现在错误的那天；非法日期
    // 应该解析失败记 0、由调用方按无效节目过滤（见壳 830 轮同款毛病）
    val formats = epgTimeFallbackFormats.get() ?: return 0
    for (format in formats) {
        try {
            val parsed = format.parse(trimmed)
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
): Boolean = keepEpgChannelNormalized(channelId, channelName, buildEpgFilterSet(filteredChannels))

/**
 * 过滤集归一化：调用方传进来的频道名/tvg-id 大小写混杂（备用源的 id
 * 是大写 CCTV1、直播源的 tvg-id 是小写 cctv1），比对时两边都得忽略
 * 大小写；解析时每个频道都现建一次归一化集合、或每次都线性扫一遍
 * 过滤集（约 112 项 × 约 500 个频道 ≈ 5 万多次短串比较）都是浪费。
 * 解析入口一次建成小写的集合，后面每次只做两次哈希查找。顶层函数
 * 方便单元测试直接调用。
 */
internal fun buildEpgFilterSet(filteredChannels: Collection<String>): Set<String> =
    filteredChannels.map { it.lowercase(Locale.ROOT) }.toSet()

/**
 * 用已归一化的过滤集判断频道要不要留：和 keepEpgChannel 同一语义，
 * 只是过滤集已提前建成小写集合，单次判断是两次哈希查找而不是线性扫。
 * 顶层函数方便单元测试直接调用。
 */
internal fun keepEpgChannelNormalized(
    channelId: String?,
    channelName: String?,
    normalizedFilter: Set<String>,
): Boolean {
    if (channelId == null || channelName == null) return false
    if (normalizedFilter.isEmpty()) return true
    // 大小写不敏感：备用源的频道 id 是大写（CCTV1）、直播源的 tvg-id
    // 是小写（cctv1），大小写敏感比会对不上、整台被滤掉
    return normalizedFilter.contains(channelName.lowercase(Locale.ROOT)) ||
        normalizedFilter.contains(channelId.lowercase(Locale.ROOT))
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
        val parseStartNs = System.nanoTime()
        // 不走 android.util.Xml.newPullParser()：它在真机上就是转调
        // XmlPullParserFactory.newInstance().newPullParser()，但在单元
        // 测试里 android.jar 的桩返回 null（isReturnDefaultValues），
        // 直接建解析器就空指针、parseFromXml 一直没法进单测。直接调
        // 工厂与真机行为一致；测试里用 kxml2 的真 KXmlParser
        val parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlString))

        // 一个 SimpleDateFormat 实例复用于全部节目（原先每个节目都新建一个，
        // 构造开销不小）；解析在单协程内顺序执行，无线程安全问题
        // 严格模式：默认宽容模式会把非法日期顺延成有效日期（2 月 30 日
        // 算成 3 月 2 日），节目会出现在错误的那天；非法日期应解析失败
        // 记 0、由 isUsableEpgProgramme 过滤（见壳 830 轮同款毛病）
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
            .apply { isLenient = false }

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
        // 先到节目、后到频道声明的节目先攒着：XMLTV 不保证 channel 标签
        // 一定在 programme 标签前面，源生成器换个顺序、先写的节目就因
        // 查不到频道被悄悄丢掉，整台显示无节目；频道声明到了就把攒着的
        // 节目一次接过去，到解析结束还没等到声明的（没名字的台、被过滤
        // 掉的台）才丢掉
        val pendingProgrammes = mutableMapOf<String, MutableList<EpgProgramme>>()
        // 过滤列表一次建成小写的集合：后面每个频道只做两次哈希查找，
        // 不再每次线性扫一遍过滤集（见 buildEpgFilterSet）
        val normalizedFilterSet = buildEpgFilterSet(filteredChannels)

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
                            keepEpgChannelNormalized(channelId, channelName, normalizedFilterSet)
                        ) {
                            channelNames[channelId] = channelName
                            // 与原先 `epgMap[id] = Epg(...)` 的语义一致：重复的 channel
                            // 标签会重置该频道的节目列表
                            channelProgrammes[channelId] = mutableListOf()
                            // 频道声明到了：把之前攒着的、归这个频道的节目一次
                            // 接过去；重复声明时攒着的已在第一次接过，不会重复接
                            pendingProgrammes.remove(channelId)?.let {
                                channelProgrammes[channelId]?.addAll(it)
                            }
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
                                // 频道已声明就直接挂进去；频道还没声明（节目写在
                                // channel 标签前面）先攒着，等声明到了再接过去；
                                // 没写 channel 属性的节目直接丢掉，和原来一致
                                // （原来 channelProgrammes[null] 查不到、也进不去）
                                val programmes = channelId?.let { channelProgrammes[it] }
                                if (programmes != null) {
                                    programmes.add(programme)
                                } else if (channelId != null) {
                                    pendingProgrammes.getOrPut(channelId) { mutableListOf() }
                                        .add(programme)
                                }
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

        log.i(
            "解析节目单完成，共${epgList.size}个频道",
            duration = (System.nanoTime() - parseStartNs).nanoseconds,
        )
        return@withContext epgList
    }

    /**
     * 拉主源、解析、过期时拉备用源兜底：主源返回 200 但数据过期（只剩
     * 前几天的节目）时最容易中招——解析成功、没有正在播的节目，面板
     * 回落到昨天那天、主界面当前节目全空，用户看到的就是“节目单没有
     * 了”。主源拉取失败时也直接试备用源；备用源也失败才抛，外层
     * getOrRefresh 会拿旧缓存顶上。备用源有正在播的节目就用备用源，
     * 否则沿用主源（面板会回落到有节目的第一天，总比空节目单强）。
     * 备用源的 XML 也走同一份 epg.xml 缓存文件：下次主源恢复后刷新
     * 时重新拉主源，不会一直粘在备用源上。
     */
    private suspend fun loadEpgList(
        xmlUrl: String,
        backupXmlUrl: String,
        filteredChannels: List<String>,
        prefetchedXml: String?,
    ): EpgList {
        // 主源拉取失败（网络错、地址错）时直接试备用源，别等外层重试
        // 整轮失败；备用源拉取也失败时原样抛，外层拿旧缓存顶上
        var fromBackup = false
        // P1：启动时已并行拉好的 XML 直接用，不再等直播源就绪后重新下载
        val primaryXml = prefetchedXml ?: try {
            epgXmlRepository.getEpgXml(xmlUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.w("主节目单源拉取失败，试备用源", e)
            fromBackup = true
            epgXmlRepository.getEpgXml(backupXmlUrl)
        }
        val primary = parseFromXml(primaryXml, filteredChannels)
        if (hasLiveProgramme(primary) || fromBackup) return primary
        // 主源解析出了数据、但里面没有正在播的节目：源数据过期了
        return try {
            val backup = parseFromXml(
                epgXmlRepository.getEpgXml(backupXmlUrl),
                filteredChannels,
            )
            selectEpgList(primary, backup).also {
                if (it === backup) log.i("主节目单源无正在播节目，已用备用源节目单")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.w("备用节目单源拉取失败，沿用主源数据", e)
            primary
        }
    }

    /**
     * 启动并行化用的节目单 XML 预下载（P1）：与 getEpgList 的刷新判断一致，
     * 只在确实需要刷新时才下载，否则返回 null、让调用方走正常路径；
     * 下载失败也返回 null（正常路径会重试并回落旧缓存），不抛错。
     * XML 下载不需要频道列表，解析才需要，所以可以和直播源加载并行跑。
     */
    suspend fun downloadEpgXmlIfNeeded(xmlUrl: String, refreshTimeThreshold: Int): String? {
        val cachedJson = getCachedData()
        // 未到时间点且 JSON 缓存可用 → getEpgList 直接用缓存，不下载
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold) {
            if (cachedEpgBeforeThreshold(cachedJson) != null) return null
        }
        // JSON 缓存是今天的、且里面有正在播的节目 → getEpgList 直接用缓存，不下载；
        // 只有今天的日期、内容却是昨天的（凌晨拉的旧数据），也要下载，
        // 否则 getEpgList 刷新时还得同步再下一遍
        val cachedList = decodeEpgListOrNull(cachedJson)
        if (cachedList != null &&
            isSameDay(System.currentTimeMillis(), getCacheLastModified()) &&
            hasLiveProgramme(cachedList)
        ) return null
        return try {
            epgXmlRepository.getEpgXml(xmlUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun getEpgList(
        xmlUrl: String,
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
        prefetchedXml: String? = null,
        backupXmlUrl: String = Constants.EPG_XML_URL_BACKUP,
    ) = withContext(Dispatchers.Default) {
        try {
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold) {
                // 未到时间点不拉取，但缓存的节目单仍可用（源文件一般覆盖
                // 多天，凌晨时段的节目多半在里面）：有缓存、且里面有正
                // 在播的节目才直接返回，别直接给空——原先每天 0 点到阈值
                // （默认 2 点）之间完全没有节目信息。没缓存（首次启动、
                // 清过缓存）或缓存解不开、或缓存里没有正在播的节目（主
                // 源过期只剩昨天数据）时不等时间点、直接走下面的刷新：
                // 不拉就一直是空节目单，要等阈值过了才恢复；拉一次比空
                // 着强，刷新时主源过期还会试备用源
                val cached = cachedEpgBeforeThreshold(getCachedData())
                if (cached != null && hasLiveProgramme(cached)) {
                    log.d("未到时间点，不刷新节目单，沿用缓存")
                    return@withContext cached
                }
                log.w("节目单缓存不可用或已过期，跳过等待直接刷新")
            }

            // P5：刷新成功时直接返回刚解析出的对象，不再把写盘的 JSON
            // 又解回来：解析出的对象编成 JSON 写缓存、随即又反序列化，
            // 白走一趟完整编解码（约 400KB）；只有缓存命中路径才走 JSON 解码
            var parsedOnRefresh: EpgList? = null
            val xmlJson = getOrRefresh({ lastModified, cacheData ->
                // 同天但解不开的缓存也算过期：截断的缓存若只按日期判
                // “未过期”，下面解码失败会直接给空节目单、要等隔天才
                // 恢复；解不开就刷新——上面“未到时间点”那条分支也是
                // 这个意思（缓存解不开时不等时间点直接刷新），只按日
                // 期判会让这条兜底落空
                // 内容过期也一样：文件是今天的、也能解开，但里面没有
                // 一档正在播的节目——凌晨拉到的还是昨天的数据，带着
                // 今天的时间戳存下来，白天全播完了；只按日期判会沿用
                // 一整天、节目单一直显示无节目。没正在播的节目就当
                // 过期、重新拉取
                val decoded = decodeEpgListOrNull(cacheData)
                !isSameDay(System.currentTimeMillis(), lastModified) ||
                    decoded == null ||
                    !hasLiveProgramme(decoded)
            }) {
                val parsed = loadEpgList(xmlUrl, backupXmlUrl, filteredChannels, prefetchedXml)
                parsedOnRefresh = parsed
                Json.encodeToString(parsed.value)
            }

            // 刷新刚成功时编解码必然能 round-trip；解不开说明拿到的是
            // getOrRefresh 刷新失败时顶上的旧坏缓存：给空节目单，别抛错
            parsedOnRefresh ?: decodeEpgListOrNull(xmlJson) ?: run {
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
