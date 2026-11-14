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

/**
 * 这次失败的是不是最后一条线路：不是时切下一条、新的 prepare 会在就
 * 绪后给提示条起计时；是时不会再调播放器、没有就绪回调，提示条必须
 * 在失败处理里自己起计时，否则一直挂在屏幕上、盖在报错屏上面。
 * 顶层函数方便单元测试直接调用。
 */
internal fun isLastUrlFailed(failedUrlIdx: Int, urlCount: Int): Boolean =
    failedUrlIdx >= urlCount - 1

/**
 * 多线路弹窗里点中正在播的那条线要不要切：点中的就是当前这条时不
 * 用再准备一次——changeCurrentIptv 会原样再播一遍，画面黑一下重新
 * 缓冲、切台提示条也跟着闪一下。断流重备那路（同台同线再准备一次）
 * 不走这里，是直接调 changeCurrentIptv，不受影响。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldSwitchUrlIdx(currentUrlIdx: Int, targetUrlIdx: Int): Boolean =
    currentUrlIdx != targetUrlIdx

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

        /**
         * 可播域名记忆攒够多少次变化才刷盘（P8）：连切台时每次都读-改-写
         * SP 太浪费，内存里攒着、够数或播起来时一次性刷
         */
        const val PLAYABLE_HOSTS_FLUSH_EVERY = 5
    }

    // P8：可播域名与上次台号的内存镜像：SP 的 StringSet 每次读写都整份
    // 复制，连切台时反复读-改-写；内存里改、攒够次数或播起来时一次性刷盘。
    // 丢了也只是“可播域名”缓存与上次台号，不影响播放
    private var playableHostsMirror: MutableSet<String>? = null
    private var playableHostsDirtyCount = 0
    private var lastIptvIdxMirror: Int? = null

    private fun playableHosts(): MutableSet<String> =
        playableHostsMirror ?: SP.iptvPlayableHostList.toMutableSet().also { playableHostsMirror = it }

    private fun markPlayableHostsChanged() {
        if (++playableHostsDirtyCount >= PLAYABLE_HOSTS_FLUSH_EVERY) flushPlayableHosts()
    }

    private fun flushPlayableHosts() {
        playableHostsMirror?.let { SP.iptvPlayableHostList = it.toSet() }
        lastIptvIdxMirror?.let { SP.iptvLastIptvIdx = it }
        playableHostsDirtyCount = 0
    }

    /**
     * 清掉可播放域名记忆：只写 SP 不够——镜像还在内存里，下次播起来
     * flush 时会把旧的写回 SP、清了等于没清。设置页那路清完调 refresh()、
     * 主界面状态重建、镜像随状态一起没了，所以那路没这个问题；快捷面板
     * 那路不清不刷新，镜像一直留着，必须在这里一并丢掉，下次读镜像时
     * 从已清空的 SP 重读
     */
    fun clearPlayableHosts() {
        playableHostsMirror = null
        playableHostsDirtyCount = 0
        SP.iptvPlayableHostList = emptySet()
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

            // 记忆可播放的域名：下标按 getOrNull 取，万一下标和频道对不上
            // 就跳过这一次记忆，不越界崩溃；播起来是稳定点，顺手把攒着的
            // 镜像刷盘（P8），连切台时没播起来的变化仍攒着
            _currentIptv.urlList.getOrNull(_currentIptvUrlIdx)?.let {
                playableHosts().add(getUrlHost(it))
                markPlayableHostsChanged()
            }
            flushPlayableHosts()

            // P2：当前台播稳后，提前拉上下相邻台的 HLS 列表，换台时少一次往返
            prefetchAdjacentPlaylists()
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
            // 按频道对象比对，不只按名字：同名不同地址的两个台（如两个分组
            // 里都有“CCTV-1”）1.5 秒内连切，旧计时器按名字认不出是换台了，
            // 会把新台的提示条提前藏掉；对象按值相等，地址不同就是不同台
            val iptv = _currentIptv
            val urlIdx = _currentIptvUrlIdx
            delay(Constants.UI_TEMP_PANEL_SCREEN_SHOW_DURATION)
            if (iptv == _currentIptv && urlIdx == _currentIptvUrlIdx) {
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
        if (!isLastUrlFailed(failedUrlIdx, _currentIptv.urlList.size)) {
            changeCurrentIptv(_currentIptv, failedUrlIdx + 1)
        } else {
            if (reportErrorIfLast && _currentIptv.urlList.isNotEmpty()) {
                val cutoff = LeanbackVideoPlayer.PlaybackException.CUTOFF
                videoPlayerState.error = "${cutoff.errorCodeName}(${cutoff.errorCode})"
            }
            // 末条线路也失败了：不会再调播放器、没有就绪回调，提示条
            // 不在这里起计时就一直挂在屏幕上、盖在报错屏上面；切台或
            // 换线时新的 prepare 会再起自己的计时，这里的计时只认当初
            // 那条线路，半路切走了不会把新台的提示条提前藏掉
            hideTempPanelAfterDelay()
        }

        // 从记忆中删除不可播放的域名：下标按 getOrNull 取，万一下标和
        // 频道对不上就跳过，不越界崩溃；只改内存镜像（P8），攒够次数再刷盘
        _currentIptv.urlList.getOrNull(failedUrlIdx)?.let {
            playableHosts().remove(getUrlHost(it))
            markPlayableHostsChanged()
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

    /**
     * 预取相邻台的 HLS 列表（P2）：只取上下台的首条地址，播放器内部
     * 只认 .m3u8、只拉列表不拉分片；同一地址重复调用时播放器内部去重
     */
    private fun prefetchAdjacentPlaylists() {
        listOf(getPrevIptv(), getNextIptv()).forEach { iptv ->
            iptv.urlList.firstOrNull()?.let { videoPlayerState.prefetchPlaylist(it) }
        }
    }

    fun changeCurrentIptv(iptv: Iptv, urlIdx: Int? = null) {
        _isPanelVisible = false

        if (iptv == _currentIptv && urlIdx == null) return

        if (iptv == _currentIptv && urlIdx != _currentIptvUrlIdx) {
            // 仅当旧频道有播放地址时才处理域名记忆（无地址的提示条目没有 urlList[0]）；
            // 下标按 getOrNull 取，万一对不上就跳过，不越界崩溃；
            // 只改内存镜像（P8），攒够次数再刷盘
            _currentIptv.urlList.getOrNull(_currentIptvUrlIdx)?.let {
                playableHosts().remove(getUrlHost(it))
                markPlayableHostsChanged()
            }
        }

        _isTempPanelVisible = true

        _currentIptv = iptv
        // 上次台号也进内存镜像（P8），和可播域名一起刷盘
        lastIptvIdxMirror = iptvGroupList.iptvIdx(_currentIptv)

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
            // 原来每比一条地址就重读一次，一个台有多条备用地址时就重复读好几次；
            // 读内存镜像（P8），不走 SP 的整份复制
            val playableHosts = playableHosts()
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