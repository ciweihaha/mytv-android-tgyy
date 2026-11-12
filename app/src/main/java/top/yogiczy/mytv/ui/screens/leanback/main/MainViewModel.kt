package top.yogiczy.mytv.ui.screens.leanback.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.data.utils.InvalidSourceUrlException
import top.yogiczy.mytv.ui.utils.SP

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
 * 不用等 10 次重试（每次隔 3 秒）跑完；网络错等其他错照常重试。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldRetryLoadFailure(cause: Throwable): Boolean =
    cause !is InvalidSourceUrlException

class LeanbackMainViewModel : ViewModel() {
    private val iptvRepository = IptvRepository()
    private val epgRepository = EpgRepository()

    private val _uiState = MutableStateFlow<LeanbackMainUiState>(LeanbackMainUiState.Loading())
    val uiState: StateFlow<LeanbackMainUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    // 刷新代号：每次 refresh() 加一。直播源与节目单拉取都是阻塞式网络
    // 调用（execute() 不响应取消），旧轮次取消后仍可能跑完；写状态前
    // 先对代号，过期轮次的结果一律丢弃，不盖掉新一轮的状态
    private var refreshGeneration = 0

    init {
        refresh()
    }

    /**
     * 重新加载直播源与节目单：设置里切换源、清除缓存后调用。取消进行中的
     * 加载并从 Loading 重新开始；主界面随状态回到 Loading 再进 Ready，
     * 内容状态随之重建，不会持有旧源的频道列表
     */
    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            _uiState.value = LeanbackMainUiState.Loading()
            refreshIptv(generation)
            refreshEpg(generation)
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
                    SP.iptvSourceUrlHistoryList -= SP.iptvSourceUrl
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

    private suspend fun refreshEpg(generation: Int) {
        // 已是过期轮次：直播源那步的写状态已被代号拦下，这里也直接
        // 跳过，别拿新源的频道单多拉一次节目单
        if (generation != refreshGeneration) return

        // 节目单开关原来没人读：关了照样拉节目单；这里真正按开关走。
        // refresh() 已把 Ready 的 epgList 重置为空，跳过即保持无节目单
        if (!SP.epgEnable) return

        // 先把 Ready 快照下来，后面再用它：节目单请求是阻塞式网络调用
        // （execute() 不响应取消），中途用户切源会让 refresh() 把状态
        // 重置回 Loading，map 里再 as Ready 会闪退；旧节目单还可能贴
        // 到新频道单上。快照不到 Ready 说明还没加载好，直接跳过
        val readyState = _uiState.value as? LeanbackMainUiState.Ready ?: return
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
                    SP.epgXmlUrlHistoryList -= SP.epgXmlUrl
                }
            }
            .map { epgList ->
                // 状态已不是当初那份 Ready（中途又刷新了）：旧结果直接
                // 丢弃，别往新状态上贴
                if (_uiState.value === readyState) {
                    _uiState.value = readyState.copy(epgList = epgList)
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