package top.yogiczy.mytv.ui.screens.leanback.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.data.repositories.iptv.EmptyIptvSourceException
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.data.utils.HttpStatusException
import top.yogiczy.mytv.data.utils.InvalidSourceUrlException
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Logger

/**
 * 节目单失败的收尾（写空节目单、删历史地址）要不要执行：只在状态还
 * 是当初那份 Ready 快照时才做。直播源那路失败时删地址包在代号检查
 * 里、节目单这路成功时加地址也包在同一检查里，唯独失败时删地址没
 * 包——过期轮次的节目单拉取是阻塞调用、取消后照样跑完，还要重试
 * 10 次、每次隔 3 秒，拖到新一轮都成功了才报失败，这时删掉的是当前
 * 正在用的好地址（新一轮成功时刚把它加回历史）。引用相等才算同一份
 * 快照：内容相同但不是同一份对象时（新一轮的 Ready）也不执行。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldApplyEpgFailure(
    currentState: LeanbackMainUiState,
    readyState: LeanbackMainUiState.Ready,
): Boolean = currentState === readyState

/**
 * 这次失败要不要重试：地址格式错重试多少次都一样（地址是读进来的那
 * 一份，改地址会走 refresh() 另起新一轮），直接不重试、报错立刻显示，
 * 不用等 10 次重试（每次隔 3 秒）跑完；直播源里没频道也一样——拉到
 * 的内容解析前已写进缓存，第 2 到第 10 次重试读到的都是同一份缓存、
 * 解析出同样的 0 个频道（只有缓存时间设成 0 时才会每次重新拉）；
 * 服务器回了明确状态码也分两类：4xx（除 408 超时、429 限流）是地址
 * 本身的问题——文件没了、没权限，重试多少次都一样；408/429 与 5xx
 * 是服务器一时的问题，照常重试。网络错等其他错照常重试。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldRetryLoadFailure(cause: Throwable): Boolean =
    when (cause) {
        is InvalidSourceUrlException, is EmptyIptvSourceException -> false
        is HttpStatusException -> cause.code == 408 || cause.code == 429 || cause.code >= 500
        else -> true
    }

/**
 * 拉取失败后要不要把这个地址从历史里删掉：历史只是设置页里点选的
 * 快捷列表，用户还能手动逐条删；网络一抖（比如 WiFi 断几秒）就把
 * 好地址删掉，用户下次只能重新手输。地址本身有问题的失败（格式错、
 * 源里没频道）才删——这种地址留着也没用。判断与要不要重试是同一条
 * 界线，直接复用。顶层函数方便单元测试直接调用。
 */
internal fun shouldRemoveFailedUrlFromHistory(cause: Throwable): Boolean =
    !shouldRetryLoadFailure(cause)

/**
 * 节目单自动重拉间隔：服务端每天 05:32 左右出新文件，白天也可能
 * 修正；app 常驻不重启时只靠启动那一次拉取，节目单会停在昨天。
 * 每小时重拉一次，流量与服务端压力都可接受
 */
internal const val EPG_AUTO_REFRESH_INTERVAL_MS = 60 * 60 * 1000L

/**
 * 回前台重拉的最小间隔：刚拉过就切出去、几秒回来不重复拉；离开
 * 超过 15 分钟再回来，服务端文件可能已更新，重拉一次
 */
internal const val EPG_FOREGROUND_REFRESH_MIN_INTERVAL_MS = 15 * 60 * 1000L

/**
 * 回前台时要不要重拉节目单：距上次成功拉取超过最小间隔就拉。
 * lastRefreshAt 为 0（还没成功拉过）时也返回 true，但调用方
 * refreshEpgOnly 只在已 Ready 时才真拉，初始加载进行中不会
 * 多拉一路。顶层函数方便单元测试直接调用。
 */
internal fun shouldRefreshEpgOnForeground(
    lastRefreshAt: Long,
    now: Long = System.currentTimeMillis(),
): Boolean = now - lastRefreshAt >= EPG_FOREGROUND_REFRESH_MIN_INTERVAL_MS

class LeanbackMainViewModel : ViewModel() {
    private val log = Logger.create(javaClass.simpleName)
    private val iptvRepository = IptvRepository()
    private val epgRepository = EpgRepository()

    private val _uiState = MutableStateFlow<LeanbackMainUiState>(LeanbackMainUiState.Loading())
    val uiState: StateFlow<LeanbackMainUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    // 节目单上次成功拉取的时间（毫秒）：定时重拉与回前台重拉都以它
    // 为准算间隔；失败不更新，下次触发继续重试
    private var lastEpgRefreshAt = 0L

    // 每小时自动重拉的循环任务；单独一路，不碰直播源、不重置界面状态
    private var epgAutoRefreshJob: Job? = null

    // 正在跑的单次节目单重拉：定时与回前台可能撞上，跑着就跳过，
    // 别并发两路写同一份缓存
    private var epgRefreshJob: Job? = null

    // 刷新代号：每次 refresh() 加一。直播源与节目单拉取都是阻塞式网络
    // 调用（execute() 不响应取消），旧轮次取消后仍可能跑完；写状态前
    // 先对代号，过期轮次的结果一律丢弃，不盖掉新一轮的状态
    private var refreshGeneration = 0

    init {
        refresh()
        startEpgAutoRefresh()
    }

    /**
     * 每小时自动重拉节目单：app 常驻不重启时，启动那一次拉取之后
     * 再也没有刷新机会，节目单停在昨天；循环每隔一小时调一次
     * refreshEpgOnly（强制刷新），失败只记日志、下个整点再试。
     * viewModelScope 取消时循环随之结束
     */
    private fun startEpgAutoRefresh() {
        epgAutoRefreshJob?.cancel()
        epgAutoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(EPG_AUTO_REFRESH_INTERVAL_MS)
                refreshEpgOnly(forceRefresh = true)
            }
        }
    }

    /**
     * 回到前台时调用（主界面 ON_RESUME）：距上次成功拉取超过 15
     * 分钟就重拉一次节目单。短时间切出去再回来不重复拉；离开久了
     * 再回来，服务端文件可能已更新、或缓存里的节目已播完，重拉
     * 一次。只在已 Ready 时真拉，初始加载进行中直接跳过
     */
    fun onForeground() {
        if (!shouldRefreshEpgOnForeground(lastEpgRefreshAt)) return
        log.d("回前台，检查节目单是否需要刷新")
        refreshEpgOnly(forceRefresh = true)
    }

    /**
     * 只重拉节目单，不动直播源、不重置界面状态：定时与回前台两路
     * 共用。getEpgList 自带过期判断（缓存不是今天、或里面没有正
     * 在播的节目就刷新），强制刷新时连“今天且有节目”的缓存也重
     * 拉——服务端文件每天更新，白天也可能修正。刷新失败只记日志、
     * 沿用旧节目单，不写空、不报错，下次触发再试；中途用户切源
     * （refresh() 另起新一轮）时，代号与快照检查会丢弃旧结果
     */
    private fun refreshEpgOnly(forceRefresh: Boolean) {
        if (!SP.epgEnable) return
        if (epgRefreshJob?.isActive == true) {
            log.d("节目单正在刷新中，跳过本次自动刷新")
            return
        }
        val readyState = _uiState.value as? LeanbackMainUiState.Ready ?: return
        val generation = refreshGeneration
        epgRefreshJob = viewModelScope.launch {
            try {
                val epgList = epgRepository.getEpgList(
                    xmlUrl = SP.epgXmlUrl,
                    filteredChannels = readyState.iptvGroupList.iptvList.flatMap {
                        listOf(it.channelName, it.tvgId)
                    }.filter { it.isNotBlank() },
                    refreshTimeThreshold = SP.epgRefreshTimeThreshold,
                    forceRefresh = forceRefresh,
                )
                if (generation != refreshGeneration) return@launch
                if (_uiState.value === readyState) {
                    _uiState.value = readyState.copy(epgList = epgList)
                    lastEpgRefreshAt = System.currentTimeMillis()
                    log.i("节目单自动刷新完成，共${epgList.size}个频道")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.w("节目单自动刷新失败，沿用旧节目单", e)
            }
        }
    }

    /**
     * 重新加载直播源与节目单：设置里切换源、清除缓存后调用。取消进行中的
     * 加载并从 Loading 重新开始；主界面随状态回到 Loading 再进 Ready，
     * 内容状态随之重建，不会持有旧源的频道列表
     */
    fun refresh() {
        refreshJob?.cancel()
        epgRefreshJob?.cancel()
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            _uiState.value = LeanbackMainUiState.Loading()
            // P1：节目单 XML 下载与直播源加载并行：XML 下载不需要频道列表，
            // 解析才需要；先在后台拉，直播源就绪后直接解析，冷启动省一段下载时间
            val epgXmlDeferred = if (SP.epgEnable) {
                async { epgRepository.downloadEpgXmlIfNeeded(SP.epgXmlUrl, SP.epgRefreshTimeThreshold) }
            } else null
            refreshIptv(generation)
            refreshEpg(generation, epgXmlDeferred)
        }
    }

    private suspend fun refreshIptv(generation: Int) {
        flow {
            emit(
                iptvRepository.getIptvGroupList(
                    sourceUrl = SP.iptvSourceUrl,
                    cacheTime = SP.iptvSourceCacheTime,
                    simplify = SP.iptvSourceSimplify,
                )
            )
        }
            .retryWhen { cause, attempt ->
                // 地址格式错不重试：重试多少次都一样，报错立刻显示
                if (!shouldRetryLoadFailure(cause)) return@retryWhen false
                if (attempt >= Constants.HTTP_RETRY_COUNT) return@retryWhen false

                // 只有当前这轮才写状态：旧轮次的拉取是阻塞调用、取消后
                // 仍可能跑完，重试提示晚于新一轮到达时，会把新源已就
                // 绪的状态盖回加载中；节目单那步的快照也对不上、结果
                // 被丢弃，界面就一直卡在加载转圈上
                if (generation == refreshGeneration) {
                    _uiState.value =
                        LeanbackMainUiState.Loading("获取远程直播源(${attempt + 1}/${Constants.HTTP_RETRY_COUNT})...")
                }
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .catch {
                if (generation == refreshGeneration) {
                    _uiState.value = LeanbackMainUiState.Error(it.message)
                    // 只有地址本身有问题的失败才从历史里删：网络抖一下
                    // 就失败时地址本身没问题，留着下次还能从历史里点
                    if (shouldRemoveFailedUrlFromHistory(it)) {
                        SP.iptvSourceUrlHistoryList -= SP.iptvSourceUrl
                    }
                }
            }
            .map {
                if (generation == refreshGeneration) {
                    _uiState.value = LeanbackMainUiState.Ready(iptvGroupList = it)
                    SP.iptvSourceUrlHistoryList += SP.iptvSourceUrl
                }
                it
            }
            .collect()
    }

    private suspend fun refreshEpg(generation: Int, epgXmlDeferred: Deferred<String?>? = null) {
        // 已是过期轮次：直播源那步的写状态已被代号拦下，这里也直接
        // 跳过，别拿新源的频道单多拉一次节目单；并行下载的 XML 也不再需要
        if (generation != refreshGeneration) {
            epgXmlDeferred?.cancel()
            return
        }

        // 节目单开关原来没人读：关了照样拉节目单；这里真正按开关走。
        // refresh() 已把 Ready 的 epgList 重置为空，跳过即保持无节目单
        if (!SP.epgEnable) {
            epgXmlDeferred?.cancel()
            return
        }

        // 先把 Ready 快照下来，后面再用它：节目单请求是阻塞式网络调用
        // （execute() 不响应取消），中途用户切源会让 refresh() 把状态
        // 重置回 Loading，map 里再 as Ready 会闪退；旧节目单还可能贴
        // 到新频道单上。快照不到 Ready 说明还没加载好，直接跳过
        val readyState = _uiState.value as? LeanbackMainUiState.Ready ?: run {
            epgXmlDeferred?.cancel()
            return
        }
        val iptvGroupList = readyState.iptvGroupList

        flow {
            emit(
                epgRepository.getEpgList(
                    xmlUrl = SP.epgXmlUrl,
                    // 过滤条件把频道名和 tvg-id 都带上：tvg-name 是纯 id
                    // （如 "cctv1"）的源，只传频道名时解析那步按 display-name
                    // 一个都对不上、节目单直接被滤空；id 也进过滤集后，
                    // display-name 或 id 任一对上就留（见 keepEpgChannel）
                    filteredChannels = iptvGroupList.iptvList.flatMap {
                        listOf(it.channelName, it.tvgId)
                    }.filter { it.isNotBlank() },
                    refreshTimeThreshold = SP.epgRefreshTimeThreshold,
                    // P1：启动时与直播源并行拉好的 XML，解析直接用
                    prefetchedXml = epgXmlDeferred?.await(),
                )
            )
        }
            .retry(Constants.HTTP_RETRY_COUNT) { cause ->
                // 地址格式错不重试：重试多少次都一样，节目单直接记空
                if (!shouldRetryLoadFailure(cause)) return@retry false
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .catch {
                // 节目单失败只记空节目单：直接在这里写回空节目单，不再
                // emit 后走 map——map 里会把刚删掉的历史地址又加回去，
                // 坏地址永远删不掉；直播源那路失败时 catch 不发数据、
                // map 不跑，删除是生效的，这里与它对齐。删地址也要包在
                // 同一检查里：过期轮次的失败不能删当前正在用的地址，
                // 见 shouldApplyEpgFailure
                if (shouldApplyEpgFailure(_uiState.value, readyState)) {
                    _uiState.value = readyState.copy(epgList = EpgList())
                    // 只有地址本身有问题的失败才从历史里删：网络抖一下
                    // 就失败时地址本身没问题，留着下次还能从历史里点
                    if (shouldRemoveFailedUrlFromHistory(it)) {
                        SP.epgXmlUrlHistoryList -= SP.epgXmlUrl
                    }
                }
            }
            .map { epgList ->
                // 状态已不是当初那份 Ready（中途又刷新了）：旧结果直接
                // 丢弃，别往新状态上贴
                if (_uiState.value === readyState) {
                    _uiState.value = readyState.copy(epgList = epgList)
                    lastEpgRefreshAt = System.currentTimeMillis()
                    SP.epgXmlUrlHistoryList += SP.epgXmlUrl
                }
            }
            .collect()
    }
}

sealed interface LeanbackMainUiState {
    data class Loading(val message: String? = null) : LeanbackMainUiState
    data class Error(val message: String? = null) : LeanbackMainUiState
    data class Ready(
        val iptvGroupList: IptvGroupList = IptvGroupList(),
        val epgList: EpgList = EpgList(),
    ) : LeanbackMainUiState
}