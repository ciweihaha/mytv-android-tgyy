package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.video.LeanbackVideoPlayerState
import top.yogiczy.mytv.ui.screens.leanback.video.rememberLeanbackVideoPlayerState
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable
import kotlin.math.max

@Stable
class LeanbackMainContentState(
    coroutineScope: CoroutineScope,
    private val videoPlayerState: LeanbackVideoPlayerState,
    private val iptvGroupList: IptvGroupList,
) : Loggable() {
    private var _currentIptv by mutableStateOf(Iptv())
    val currentIptv get() = _currentIptv

    private var _currentIptvUrlIdx by mutableIntStateOf(0)
    val currentIptvUrlIdx get() = _currentIptvUrlIdx

    private var _isPanelVisible by mutableStateOf(false)
    var isPanelVisible
        get() = _isPanelVisible
        set(value) {
            _isPanelVisible = value
        }

    private var _isSettingsVisible by mutableStateOf(false)
    var isSettingsVisible
        get() = _isSettingsVisible
        set(value) {
            _isSettingsVisible = value
        }

    private var _isTempPanelVisible by mutableStateOf(false)
    var isTempPanelVisible
        get() = _isTempPanelVisible
        set(value) {
            _isTempPanelVisible = value
        }

    private var _isQuickPanelVisible by mutableStateOf(false)
    var isQuickPanelVisible
        get() = _isQuickPanelVisible
        set(value) {
            _isQuickPanelVisible = value
        }

    init {
        changeCurrentIptv(iptvGroupList.iptvAt(SP.iptvLastIptvIdx)
            ?: iptvGroupList.firstOrNull()?.iptvList?.firstOrNull() ?: Iptv())

        videoPlayerState.onReady {
            coroutineScope.launch {
                val name = _currentIptv.name
                val urlIdx = _currentIptvUrlIdx
                delay(Constants.UI_TEMP_PANEL_SCREEN_SHOW_DURATION)
                if (name == _currentIptv.name && urlIdx == _currentIptvUrlIdx) {
                    _isTempPanelVisible = false
                }
            }

            // 记忆可播放的域名
            if (_currentIptv.urlList.isNotEmpty()) {
                SP.iptvPlayableHostList += getUrlHost(_currentIptv.urlList[_currentIptvUrlIdx])
            }
        }

        videoPlayerState.onError {
            // 先记下失败的下标：changeCurrentIptv 切线路后下标已变，
            // 原先直接按新下标删域名记忆，删掉的是刚切过去那条还没
            // 失败的线路；切线路时内部已删过失败那条，这里再删一次
            // 同一值（Set 去重，无害），末条失败时则由这里删
            val failedUrlIdx = _currentIptvUrlIdx
            if (failedUrlIdx < _currentIptv.urlList.size - 1) {
                changeCurrentIptv(_currentIptv, failedUrlIdx + 1)
            }

            // 从记忆中删除不可播放的域名
            if (_currentIptv.urlList.isNotEmpty()) {
                SP.iptvPlayableHostList -= getUrlHost(_currentIptv.urlList[failedUrlIdx])
            }
        }

        videoPlayerState.onCutoff {
            changeCurrentIptv(_currentIptv, _currentIptvUrlIdx)
        }
    }

    private fun getPrevIptv(): Iptv {
        val currentIndex = iptvGroupList.iptvIdx(_currentIptv)
        // 上一台：按下标逐组找，不再先把全部分组拼成一张大表再取——
        // 每次按上下键都拼一次，千级频道源下白白分配一次全量列表
        return iptvGroupList.iptvAt(currentIndex - 1)
            ?: iptvGroupList.lastOrNull()?.iptvList?.lastOrNull() ?: Iptv()
    }

    private fun getNextIptv(): Iptv {
        val currentIndex = iptvGroupList.iptvIdx(_currentIptv)
        return iptvGroupList.iptvAt(currentIndex + 1)
            ?: iptvGroupList.firstOrNull()?.iptvList?.firstOrNull() ?: Iptv()
    }

    fun changeCurrentIptv(iptv: Iptv, urlIdx: Int? = null) {
        _isPanelVisible = false

        if (iptv == _currentIptv && urlIdx == null) return

        if (iptv == _currentIptv && urlIdx != _currentIptvUrlIdx) {
            // 仅当旧频道有播放地址时才处理域名记忆（无地址的提示条目没有 urlList[0]）
            if (_currentIptv.urlList.isNotEmpty()) {
                SP.iptvPlayableHostList -= getUrlHost(_currentIptv.urlList[_currentIptvUrlIdx])
            }
        }

        _isTempPanelVisible = true

        _currentIptv = iptv
        SP.iptvLastIptvIdx = iptvGroupList.iptvIdx(_currentIptv)

        // 无可用播放地址的频道（如不支持的源格式返回的 m3u/tvbox 提示条目）：
        // 只切换显示，不调用播放器——否则 urlList[0] 越界崩溃
        if (iptv.urlList.isEmpty()) {
            _currentIptvUrlIdx = 0
            return
        }

        _currentIptvUrlIdx = if (urlIdx == null) {
            // 优先从记忆中选择可播放的域名：域名表只从设置里读一次记下来，
            // 原来每比一条地址就重读一次，一个台有多条备用地址时就重复读好几次
            val playableHosts = SP.iptvPlayableHostList
            max(0, _currentIptv.urlList.indexOfFirst {
                playableHosts.contains(getUrlHost(it))
            })
        } else {
            (urlIdx + _currentIptv.urlList.size) % _currentIptv.urlList.size
        }

        val url = iptv.urlList[_currentIptvUrlIdx]
        log.d("播放${iptv.name}（${_currentIptvUrlIdx + 1}/${_currentIptv.urlList.size}）: $url")

        videoPlayerState.prepare(url)
    }

    fun changeCurrentIptvToPrev() {
        changeCurrentIptv(getPrevIptv())
    }

    fun changeCurrentIptvToNext() {
        changeCurrentIptv(getNextIptv())
    }
}

@Composable
fun rememberLeanbackMainContentState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    videoPlayerState: LeanbackVideoPlayerState = rememberLeanbackVideoPlayerState(),
    iptvGroupList: IptvGroupList = IptvGroupList(),
) = remember {
    LeanbackMainContentState(
        coroutineScope = coroutineScope,
        videoPlayerState = videoPlayerState,
        iptvGroupList = iptvGroupList,
    )
}

// 取地址的域名：没有写协议的地址（如 192.168.1.1:8080/live），原来
// substringAfter("://", "") 直接拿空串——所有没写协议的地址的可播
// 放记忆都混成同一个空串：一个能播，全都被当成能播；一个播不
// 了，全都被清掉。现在没有协议就取第一段（域名或 IP:端口），
// 和带协议的地址取法对齐
internal fun getUrlHost(url: String): String {
    return if (url.contains("://")) url.substringAfter("://").substringBefore("/")
    else url.substringBefore("/")
}