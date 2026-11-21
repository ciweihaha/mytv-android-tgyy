package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException as Media3PlaybackException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvGroupIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.ui.screens.leanback.video.LeanbackVideoPlayerState
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer
import top.yogiczy.mytv.ui.screens.leanback.video.rememberLeanbackVideoPlayerState
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.utils.Loggable
import kotlin.math.max

/**
 * 断流计数：同一条线路短时间内反复断流就放弃重备、走换线/报错。
 * 窗口按加载超时的 6 倍算（180 秒）：每个断流间隔约一个加载超时、
 * 至多不到两倍（再长加载超时先报了错，走的不是断流这条路），
 * 窗口外的旧断流先清掉——播稳一阵子后偶尔一次源抖不该和几小时前
 * 的记录叠加计数。顶层函数方便单元测试直接调用。
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

/**
 * 切线路下标归一化：上一条（-1）绕到最后一条、下一条越过末条绕回
 * 第一条，中间原样返回。调用方只传 -1 到条数之间（上一条/下一条/
 * 失败切下一条/面板点选），更深的负数到不了这里。
 * 顶层函数方便单元测试直接调用。
 */
internal fun normalizeIptvUrlIdx(urlIdx: Int, urlCount: Int): Int =
    (urlIdx + urlCount) % urlCount

/**
 * 重试耗尽后末条线路失败该不该自动重调同一频道：瞬时网络错
 * （2004/2001/2002）和直播位置落后这两类“重试型”错误会走到
 * 重试耗尽——播放器对它们先重试 5 次、耗尽才上报；加载超时也
 * 算：播放器对它不做内部重试（看门狗一到直接上报），冷启动第
 * 一次拉流慢（SCF 302 可达 10 秒 + 首个分片慢）很容易撞上
 * 30 秒看门狗，报错就挂死、用户只能退出重进——重调一次（那
 * 时网络已热）大多能播起来，正好就是用户手动做的事。容器损坏
 * （3001）也算：分片本身内容坏了（CDN 坏窗口里给的 TS 分片
 * HTTP 200、长度完整、但 PES 层损坏，播放器一个轨道都解析不
 * 出来），不是地址错、不是格式不支持——重调前会重解析拿新节
 * 目单，新分片大概率已干净，坏窗口是分钟级的；重调一次还坏
 * 就显示错误。其他错误（解码、清单损坏、DRM、CUTOFF）是直接
 * 判死刑或另有处理，重调同一条线大概率还是同一个错，不自动重
 * 调、直接显示错误：清单损坏（3002）是列表本身坏了，重拉同一
 * 份源大概率还是坏清单；容器不支持（3003）播放器层已试过三种
 * 容器。同一轮失败只重调一次（autoRetuneDone），再失败就显示
 * 错误，不循环。
 * 顶层函数方便单元测试直接调用。
 */
internal fun shouldAutoRetuneAfterExhaustion(errorCode: Int?): Boolean =
    errorCode == Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        errorCode == Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        errorCode == Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        errorCode == Media3PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        errorCode == LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT.errorCode

/**
 * 重解析后在新列表里找当前频道这条线的地址：同一份直播源重拉重解析，
 * 频道名不变；先按 name 找，找不到再按 channelName（节目单用的名）
 * 兜底。取它同一下标的地址——下标对不上（新列表里这台的线变少了）
 * 或频道没了就返回 null，重解析失败，走旧地址重调、再失败正常上报。
 * 先在当前分组里找：两个分组里常有同名不同地址的台（如两个分组里
 * 都有“CCTV-1”），全表 firstOrNull 会拿到别组那条线；分组名在新
 * 列表里找不到（源改了分组名）才回退到全表找。groupName 为空时不
 * 按分组找。顶层函数方便单元测试直接调用。
 */
internal fun resolveFreshChannelUrl(
    current: Iptv,
    urlIdx: Int,
    freshGroupList: IptvGroupList,
    groupName: String? = null,
): String? {
    val scope = groupName?.takeIf { it.isNotBlank() }?.let { name ->
        freshGroupList.firstOrNull { it.name == name }?.iptvList
    } ?: freshGroupList.iptvList
    val fresh = scope.firstOrNull {
        it.name == current.name ||
            (current.channelName.isNotBlank() && it.channelName == current.channelName)
    } ?: return null
    return fresh.urlList.getOrNull(urlIdx)
}

/**
 * 重调时用哪条地址：新解析到地址就用新地址，解析不到（null/空串）
 * 就用旧地址重调一次、再失败正常上报。纯算式，抽出来方便单元测试
 * 锁死两条分支。
 */
internal fun retuneUrl(currentUrl: String, freshUrl: String?): String =
    freshUrl?.takeIf { it.isNotBlank() } ?: currentUrl

/**
 * 默认的重解析：重新拉一次直播源（绕过 1 小时文件缓存，cacheTime = 0
 * 强制刷新）再解析，在新列表里找当前频道这条线的地址。groupName 是
 * 当前频道在旧列表里的分组名，新列表里先在同组找、组名没了再全表找；
 * 为空时不按分组找。拉不到、解析失败、频道没了都返回 null——调用方
 * 用旧地址重调、再失败正常上报，不在这里抛错；取消信号
 * （CancellationException）原样抛出、不吞掉——解析中退出界面或切台
 * 取消时任务直接死，不再调 changeCurrentIptv。顶层 suspend 函数，
 * fetch 参数单测传假实现替换。
 */
internal suspend fun defaultResolveFreshChannelUrl(
    iptv: Iptv,
    urlIdx: Int,
    groupName: String? = null,
    fetch: suspend () -> IptvGroupList = {
        IptvRepository().getIptvGroupList(
            sourceUrl = SP.iptvSourceUrl,
            cacheTime = 0L,
            simplify = SP.iptvSourceSimplify,
        )
    },
): String? {
    return try {
        resolveFreshChannelUrl(iptv, urlIdx, fetch(), groupName)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}

@Stable
class LeanbackMainContentState(
    private val coroutineScope: CoroutineScope,
    private val videoPlayerState: LeanbackVideoPlayerState,
    private val iptvGroupList: IptvGroupList,
    /**
     * 切到新频道时的回调：主界面用它记最近观看（经 SettingsViewModel
     * 写 SP，面板直接读到新名单）。同台换线/断流重备不算切台，不回调；
     * 无播放地址的占位频道（源格式不支持的提示条目）也不回调。
     */
    private val onRecentChannel: (Iptv) -> Unit = {},
    /**
     * 重调前重解析频道地址拿新链接：默认走真的重拉（绕过缓存再拉一次
     * 直播源）；单测传假实现。返回 null 表示重解析失败，用旧地址重调。
     * 第三个参数是当前频道在旧列表里的分组名，新列表里先在同组找。
     */
    private val resolveFreshUrl: (suspend (Iptv, Int, String?) -> String?) = ::defaultResolveFreshChannelUrl,
) : Loggable() {
    companion object {
        /**
         * 断流多少次后放弃重备：和播放器里直播位置落后重试上限（5 次）不同，
         * 断流是位置不动的独立判断，第 21 次断流时放弃（见 nextCutoffStreak）；
         * 断流放弃后不自动重调（见 handleCurrentUrlFailed），只报 CUTOFF。
         * 20 次不是拍脑袋：凤凰那种每条连接几秒就断、位置 30 秒不动就
         * 判一次断流的源，180 秒窗口里稳态就有约 5 次；3 次 72 秒就烧完、
         * 照样报 CUTOFF（v2.2.29 平板实测：64 秒还在播，72 秒已报）。
         * 20 次是稳态的 4 倍余量，正常抖动永远碰不到，只防几秒内连断
         * 这种真死源。真正死掉的源不用怕上限太高：播不起来根本到不了
         * 就绪、位置不动，30 秒加载超时那路会先报；上限只管“播几秒
         * 又断流”这种半死不活的线
         */
        const val MAX_CUTOFFS_BEFORE_GIVE_UP = 20

        /**
         * 重试耗尽后自动重调同一频道的等待：5 秒，给上游票据轮换留出
         * 恢复时间；只排队一次、总等待不超过 AUTO_RETUNE_MAX_WAIT_MS
         */
        const val AUTO_RETUNE_DELAY_MS = 5000L

        /**
         * 自动重调总等待上限：10 秒。单次等待 5 秒即满足上限，不叠加、
         * 不重复排队；autoRetuneDone 保证同一轮失败最多重调一次
         */
        const val AUTO_RETUNE_MAX_WAIT_MS = 10000L

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

    // 重试耗尽后自动重调的排队任务：等 5 秒再重调同一频道；等待中切台
    // 就取消，不在新台上误触发
    private var autoRetuneJob: Job? = null

    // 本轮失败有没有用过自动重调：同一轮失败最多重调一次，重调后的新
    // 尝试再失败就直接显示错误，不循环；播稳（READY）或换台/换线后重置
    private var autoRetuneDone = false

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

            // 播稳了：上一轮失败的自动重调资格重算，之后再失败可以再重调一次
            autoRetuneDone = false

            // 记忆可播放的域名：下标按 getOrNull 取，万一下标和频道对不上
            // 就跳过这一次记忆，不越界崩溃；播起来是稳定点，顺手把攒着的
            // 镜像刷盘（P8），连切台时没播起来的变化仍攒着
            // 空域名不记：没写协议又没内容的地址 getUrlHost 给空串，记进
            // 去后所有空域名的地址都会被当成可播，下次切台优先选到死地址上
            _currentIptv.urlList.getOrNull(_currentIptvUrlIdx)?.let {
                getUrlHost(it).takeIf { host -> host.isNotEmpty() }?.let { host ->
                    playableHosts().add(host)
                    markPlayableHostsChanged()
                }
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
     * 重试耗尽后自动重调同一频道：等 5 秒（AUTO_RETUNE_DELAY_MS）再重调，
     * 给上游票据轮换留出恢复时间；只排队一次，总等待不超过 10 秒上限。
     * 重调走和手动切台同一条路 changeCurrentIptv（同台同线再准备一次），
     * 重新请求 SCF 拿新票据；重调后的新尝试重新按正常重试预算走，再失败
     * 就显示错误，不再重调。等待中用户切台/换线就取消，不在新台上误触发
     */
    private fun scheduleAutoRetune() {
        autoRetuneJob?.cancel()
        val iptv = _currentIptv
        val urlIdx = _currentIptvUrlIdx
        // 报错弹窗是 triggerError 时就挂上的：重调是“还没判死刑”，先把
        // 弹窗清掉、换成“正在重连…”；重调后的新尝试再失败会重新报
        videoPlayerState.error = null
        videoPlayerState.isReconnecting = true
        autoRetuneJob = coroutineScope.launch {
            delay(AUTO_RETUNE_DELAY_MS)
            autoRetuneJob = null
            // 等待中切走了就不再重调旧台
            if (iptv != _currentIptv || urlIdx != _currentIptvUrlIdx) {
                videoPlayerState.isReconnecting = false
                return@launch
            }
            // 重调前重解析频道地址拿新链接：旧链接的票据可能已过期
            // （河南卫视这类分片签名约 5 分钟就失效，之后分片时而
            // 404 时而 200），一直用旧链接重试 5 次都白试；重新拉
            // 一次直播源再解析，拿到新地址再播。解析不到（拉取失败、
            // 频道没了）就用旧地址重调一次，再失败正常上报、不循环
            val oldUrl = _currentIptv.urlList.getOrNull(urlIdx).orEmpty()
            // 当前频道在旧列表里的分组：两个分组里常有同名不同地址的台，
            // 重解析只在同组里找，找不到组名（源改了分组名、或频道已不在
            // 旧列表里）才全表找；iptvGroupIdx 找不到回 -1，组名取 null
            val groupName = iptvGroupList.getOrNull(iptvGroupList.iptvGroupIdx(iptv))?.name
            val freshUrl = try {
                resolveFreshUrl(iptv, urlIdx, groupName)
            } catch (e: Exception) {
                // 取消信号原样抛出：解析中退出界面时任务直接死，别吞掉继续重调
                if (e is CancellationException) throw e
                null
            }
            // 解析也要时间，解析完再认一次台：解析中切走了就不动
            if (iptv != _currentIptv || urlIdx != _currentIptvUrlIdx) {
                videoPlayerState.isReconnecting = false
                return@launch
            }
            videoPlayerState.isReconnecting = false
            val targetUrl = retuneUrl(oldUrl, freshUrl)
            if (targetUrl.isNotBlank() && targetUrl != oldUrl &&
                urlIdx in _currentIptv.urlList.indices
            ) {
                // 只换地址、不算换台：先把新地址写回当前频道，再走
                // 原来的重调那条路；这样 isNewChannel 为假，不记最
                // 近观看，autoRetuneDone 也不重置，不会无限重调
                _currentIptv = _currentIptv.copy(
                    urlList = _currentIptv.urlList.toMutableList().also { it[urlIdx] = targetUrl },
                )
            }
            changeCurrentIptv(_currentIptv, _currentIptvUrlIdx)
        }
    }

    /**
     * 当前线路失败：有下一条就切过去，失败的域名从可播放记忆里删掉。
     * 末条失败时，报错那路播放器自己已报了错误，这里只删记忆；断流
     * 放弃那路（reportErrorIfLast）要自己报一个，否则界面一直停在
     * 冻住的画面上、也不切台。末条失败且是重试型错误耗尽（瞬时网络错/位置落后）、
     * 容器损坏（3001）或加载超时时，等 5 秒自动重调同一频道、模拟手动切台——
     * 只有一条线路的台手动切台重拉就能恢复；只重调一次，再失败就显示错误
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
            if (!reportErrorIfLast && !autoRetuneDone &&
                shouldAutoRetuneAfterExhaustion(videoPlayerState.lastErrorCode)
            ) {
                autoRetuneDone = true
                scheduleAutoRetune()
            } else if (reportErrorIfLast && _currentIptv.urlList.isNotEmpty()) {
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

        // 是不是真换了台：同台换线、断流重备（同台同线再准备一次）不算
        // 换台，最近观看只记真换台；下面无地址的占位频道提前返回，不记
        val isNewChannel = iptv != _currentIptv

        // 排队中的自动重调被任何一次真正的切台/换线取代：手动切走了，旧台
        // 的重调不能再在新台上触发；同台同线重备（断流/自动重调本身）也先
        // 清掉残留任务——开火的任务已在等完后把引用置空，这里只是清残留。
        // 注意这段必须在上面那个空操作提前返回之后：只是在面板里又点了一
        // 次正在播的台（空操作）不该取消排队中的重调，否则画面会冻住、
        // 报错也被清掉了、重连提示也没了，用户只能干等
        autoRetuneJob?.cancel()
        autoRetuneJob = null
        videoPlayerState.isReconnecting = false

        // 换到别的台/线，重调资格重算：新台的新一轮失败可以再自动重调一次；
        // 同台同线重备不重置——自动重调那次的资格必须留到重调后的新尝试
        // 再失败时才耗尽，否则同一次失败能无限重调
        if (iptv != _currentIptv || (urlIdx != null && urlIdx != _currentIptvUrlIdx)) {
            autoRetuneDone = false
        }

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

        // 真换了台才记最近观看：换线/重备不记，无地址的占位频道上面已返回
        if (isNewChannel) onRecentChannel(iptv)

        _currentIptvUrlIdx = if (urlIdx == null) {
            // 优先从记忆中选择可播放的域名：域名表只从设置里读一次记下来，
            // 原来每比一条地址就重读一次，一个台有多条备用地址时就重复读好几次；
            // 读内存镜像（P8），不走 SP 的整份复制
            val playableHosts = playableHosts()
            max(0, _currentIptv.urlList.indexOfFirst {
                playableHosts.contains(getUrlHost(it))
            })
        } else {
            normalizeIptvUrlIdx(urlIdx, _currentIptv.urlList.size)
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
    onRecentChannel: (Iptv) -> Unit = {},
) = remember {
    LeanbackMainContentState(
        coroutineScope = coroutineScope,
        videoPlayerState = videoPlayerState,
        iptvGroupList = iptvGroupList,
        onRecentChannel = onRecentChannel,
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