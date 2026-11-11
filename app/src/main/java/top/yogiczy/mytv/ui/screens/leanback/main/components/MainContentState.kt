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
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer
import top.yogiczy.mytv.ui.screens.leanback.video.rememberLeanbackVideoPlayerState
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable
import kotlin.math.max

/**
 * 断流计数：同一条线路短时间内反复断流就放弃重备、走换线/报错。
 * 窗口按加载超时的 6 倍算：4 次断流跨 3 个间隔，每个间隔约一个加载
 * 超时、至多不到两倍（再长加载超时先报了错，走的不是断流这条路），
 * 窗口小于这个数会把最早那次先清掉、永远数不到 4 次。
 * 顶层函数方便单元测试直接调用。
 */
internal fun nextCutoffStreak(
    previous: List<Long>,
    now: Long,
    windowMs: Long,
    maxCutoffs: Int,
): Pair<List<Long>, Boolean> {
    val kept = previous.filter { now - it <= windowMs } + now
    return kept to (kept.size > maxCutoffs)
}

@Stable
class LeanbackMainContentState(
    private val coroutineScope: CoroutineScope,
    private val videoPlayerState: LeanbackVideoPlayerState,
    private val iptvGroupList: IptvGroupList,
) : Loggable() {
    companion object {
        /**
         * 断流多少次后放弃重备：和播放器里直播位置落后重试上限（3 次）一致，
         * 第 4 次断流时放弃（见 nextCutoffStreak）
         */
        const val MAX_CUTOFFS_BEFORE_GIVE_UP = 3
    }

    private var _currentIptv by mutableStateOf(Iptv())
    val currentIptv get() = _currentIptv

    private var _currentIptvUrlIdx by mutableIntStateOf(0)
    val currentIptvUrlIdx get() = _currentIptvUrlIdx

    // 最近几次断流的时间戳（同一条线路的）：切台/换线后目标变了就重数
    private var cutoffTarget: Pair<Iptv, Int>? = null
    private var cutoffTimestamps: List<Long> = emptyList()

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
            hideTempPanelAfterDelay()

            // 记忆可播放的域名
            if (_currentIptv.urlList.isNotEmpty()) {
                SP.iptvPlayableHostList += getUrlHost(_currentIptv.urlList[_currentIptvUrlIdx])
            }
        }

        videoPlayerState.onError {
            handleCurrentUrlFailed()
        }

        videoPlayerState.onCutoff {
            // 断流（位置一直不前进）先重备同一条线路；同一条线路短时间
            // 内反复断流（见 nextCutoffStreak），就当这条线路死了，走和
            // 报错一样的换线；末条线路时报错误、不再重备——否则播起来
            // 又冻住的源会每隔一个加载超时就闪一下、永远不换线
            val now = System.currentTimeMillis()
            val target = _currentIptv to _currentIptvUrlIdx
            if (cutoffTarget != target) {
                cutoffTarget = target
                cutoffTimestamps = emptyList()
            }
            val (streak, giveUp) = nextCutoffStreak(
                cutoffTimestamps,
                now,
                6 * SP.videoPlayerLoadTimeout,
                MAX_CUTOFFS_BEFORE_GIVE_UP,
            )
            cutoffTimestamps = streak
            if (giveUp) {
                cutoffTarget = null
                cutoffTimestamps = emptyList()
                handleCurrentUrlFailed(reportErrorIfLast = true)
            } else {
                changeCurrentIptv(_currentIptv, _currentIptvUrlIdx)
            }
        }
    }

    /**
     * 切台提示条 1.5 秒后自动隐藏：正常频道在播放器就绪后起计时；无地址的
     * 频道不调播放器、没有就绪回调，也要在这里起计时——否则提示条一直挂
     * 在屏幕上不消失。到期时仍是同一频道才隐藏，半路切走的不动
     */
    private fun hideTempPanelAfterDelay() {
        coroutineScope.launch {
            val name = _currentIptv.name
            val urlIdx = _currentIptvUrlIdx
            delay(Constants.UI_TEMP_PANEL_SCREEN_SHOW_DURATION)
            if (name == _currentIptv.name && urlIdx == _currentIptvUrlIdx) {
                _isTempPanelVisible = false
            }
        }
    }

    /**
     * 当前线路失败：有下一条就切过去，失败的域名从可播放记忆里删掉。
     * 末条失败时，报错那路播放器自己已报了错误，这里只删记忆；断流
     * 放弃那路（reportErrorIfLast）要自己报一个，否则界面一直停在
     * 冻住的画面上、也不切台
     */
    private fun handleCurrentUrlFailed(reportErrorIfLast: Boolean = false) {
        // 先记下失败的下标：changeCurrentIptv 切线路后下标已变，
        // 原先直接按新下标删域名记忆，删掉的是刚切过去那条还没
        // 失败的线路；切线路时内部已删过失败那条，这里再删一次
        // 同一值（Set 去重，无害），末条失败时则由这里删
        val failedUrlIdx = _currentIptvUrlIdx
        if (failedUrlIdx < _currentIptv.urlList.size - 1) {
            changeCurrentIptv(_currentIptv, failedUrlIdx + 1)
        } else if (reportErrorIfLast && _currentIptv.urlList.isNotEmpty()) {
            val cutoff = LeanbackVideoPlayer.PlaybackException.CUTOFF
            videoPlayerState.error = "${cutoff.errorCodeName}(${cutoff.errorCode})"
        }

        // 从记忆中删除不可播放的域名
        if (_currentIptv.urlList.isNotEmpty()) {
            SP.iptvPlayableHostList -= getUrlHost(_currentIptv.urlList[failedUrlIdx])
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
        // 只切换显示，不调用播放器——否则 urlList[0] 越界崩溃；不调播放器
        // 就没有就绪回调，这里直接给提示条起计时，不然它一直不消失
        if (iptv.urlList.isEmpty()) {
            _currentIptvUrlIdx = 0
            hideTempPanelAfterDelay()
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
    val host = if (url.contains("://")) url.substringAfter("://").substringBefore("/")
    else url.substringBefore("/")
    // 带账号密码的地址（如 http://user:pass@host/live）：@ 之前是账号密码，
    // 不能当成域名记进可播放记忆——既会把密码存进设置，也让同一台换个
    // 账号就认不出来；按最后一个 @ 分，密码里带 @ 的也认得对
    return host.substringAfterLast("@")
}