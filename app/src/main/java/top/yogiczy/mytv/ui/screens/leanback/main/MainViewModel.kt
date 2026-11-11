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
import top.yogiczy.mytv.ui.utils.SP

class LeanbackMainViewModel : ViewModel() {
    private val iptvRepository = IptvRepository()
    private val epgRepository = EpgRepository()

    private val _uiState = MutableStateFlow<LeanbackMainUiState>(LeanbackMainUiState.Loading())
    val uiState: StateFlow<LeanbackMainUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

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
        refreshJob = viewModelScope.launch {
            _uiState.value = LeanbackMainUiState.Loading()
            refreshIptv()
            refreshEpg()
        }
    }

    private suspend fun refreshIptv() {
        flow {
            emit(
                iptvRepository.getIptvGroupList(
                    sourceUrl = SP.iptvSourceUrl,
                    cacheTime = SP.iptvSourceCacheTime,
                    simplify = SP.iptvSourceSimplify,
                )
            )
        }
            .retryWhen { _, attempt ->
                if (attempt >= Constants.HTTP_RETRY_COUNT) return@retryWhen false

                _uiState.value =
                    LeanbackMainUiState.Loading("获取远程直播源(${attempt + 1}/${Constants.HTTP_RETRY_COUNT})...")
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .catch {
                _uiState.value = LeanbackMainUiState.Error(it.message)
                SP.iptvSourceUrlHistoryList -= SP.iptvSourceUrl
            }
            .map {
                _uiState.value = LeanbackMainUiState.Ready(iptvGroupList = it)
                SP.iptvSourceUrlHistoryList += SP.iptvSourceUrl
                it
            }
            .collect()
    }

    private suspend fun refreshEpg() {
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
                    filteredChannels = iptvGroupList.iptvList.map { it.channelName },
                    refreshTimeThreshold = SP.epgRefreshTimeThreshold,
                )
            )
        }
            .retry(Constants.HTTP_RETRY_COUNT) { delay(Constants.HTTP_RETRY_INTERVAL); true }
            .catch {
                emit(EpgList())
                SP.epgXmlUrlHistoryList -= SP.epgXmlUrl
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