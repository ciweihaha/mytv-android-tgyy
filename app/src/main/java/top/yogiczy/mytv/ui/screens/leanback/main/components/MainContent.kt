package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.channelNoText
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvAt
import top.yogiczy.mytv.ui.screens.leanback.classicpanel.LeanbackClassicPanelScreen
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackVisible
import top.yogiczy.mytv.ui.screens.leanback.monitor.LeanbackMonitorScreen
import top.yogiczy.mytv.ui.screens.leanback.panel.LeanbackPanelChannelNoSelectScreen
import top.yogiczy.mytv.ui.screens.leanback.panel.LeanbackPanelDateTimeScreen
import top.yogiczy.mytv.ui.screens.leanback.panel.LeanbackPanelScreen
import top.yogiczy.mytv.ui.screens.leanback.panel.LeanbackPanelTempScreen
import top.yogiczy.mytv.ui.screens.leanback.panel.rememberLeanbackPanelChannelNoSelectState
import top.yogiczy.mytv.ui.screens.leanback.quickpanel.LeanbackQuickPanelScreen
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsScreen
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsViewModel
import top.yogiczy.mytv.ui.screens.leanback.toast.LeanbackToastState
import top.yogiczy.mytv.ui.screens.leanback.update.LeanbackUpdateScreen
import top.yogiczy.mytv.ui.screens.leanback.video.LeanbackVideoScreen
import top.yogiczy.mytv.ui.screens.leanback.video.rememberLeanbackVideoPlayerState
import top.yogiczy.mytv.ui.utils.SP
import top.yogiczy.mytv.ui.utils.handleLeanbackDragGestures
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents
import top.yogiczy.mytv.ui.utils.safeLeanbackInvoke

@Composable
fun LeanbackMainContent(
    modifier: Modifier = Modifier,
    onBackPressed: () -> Unit = {},
    iptvGroupList: IptvGroupList = IptvGroupList(),
    epgList: EpgList = EpgList(),
    settingsViewModel: LeanbackSettingsViewModel = viewModel(),
) {
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()

    val videoPlayerState = rememberLeanbackVideoPlayerState(
        defaultAspectRatioProvider = {
            when (settingsViewModel.videoPlayerAspectRatio) {
                SP.VideoPlayerAspectRatio.ORIGINAL -> null
                SP.VideoPlayerAspectRatio.SIXTEEN_NINE -> 16f / 9f
                SP.VideoPlayerAspectRatio.FOUR_THREE -> 4f / 3f
                SP.VideoPlayerAspectRatio.AUTO -> {
                    // 宽高比是 宽/高：原先写反成了 高/宽，选"自动拉伸"后画面会被
                    // 压成竖条（16:9 的电视上是 0.5625 而不是 1.78）；与快捷面板
                    // 的自动拉伸（宽/高）保持一致
                    configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
                }
            }
        }
    )
    val mainContentState = rememberLeanbackMainContentState(
        videoPlayerState = videoPlayerState,
        iptvGroupList = iptvGroupList,
    )

    // 设置里改画面比例后立即生效：defaultAspectRatioProvider 只在分辨率变化
    // （onResolution）时才被求值，不加这一处要等切台才变
    LaunchedEffect(settingsViewModel.videoPlayerAspectRatio) {
        videoPlayerState.applyDefaultAspectRatio()
    }
    // 屏幕尺寸变了也立即重算：provider 每次重组都会换成最新的（捕获了
    // 当时的 configuration），但只换不重算——“自动拉伸”仍按旧屏幕尺寸；
    // 分屏、折叠、改显示尺寸这类不重建活动的配置变化也要立刻跟上
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        videoPlayerState.applyDefaultAspectRatio()
    }
    val panelChannelNoSelectState = rememberLeanbackPanelChannelNoSelectState(
        onChannelNoConfirm = {
            val channelNo = it.toInt() - 1

            // 输台号选台：按下标逐组找，不再先把全部分组拼成一张大表再取
            iptvGroupList.iptvAt(channelNo)?.let { mainContentState.changeCurrentIptv(it) }
        }
    )

    // 收藏名单只转一次记下来：原来传给面板的是一句“每次取都现转成不可变
    // 列表”，经典面板每按一次遥控挪焦点、父界面一刷新就调它一次，等于
    // 每按一次键就把整份收藏名单复制一遍，复制完只拿去跟旧名单比对是
    // 不是同一份；现在名单本身变了才重转，增减收藏会换一个新名单对象、
    // 重组时重新转，不会记旧。普通面板那边只在打开收藏或点收藏按钮时
    // 才取，也顺带直接拿记好的这一份
    val iptvFavoriteImmutableList = remember(settingsViewModel.iptvChannelFavoriteList) {
        settingsViewModel.iptvChannelFavoriteList.toImmutableList()
    }

    // 收藏开关只写一份共用：原来普通面板和经典面板各写了一份一模一样的
    // 收藏/取消收藏逻辑（开关没开直接返回、否则按频道名增减名单并弹提示），
    // 两份改一处容易漏另一处；现在抽成一个共用的回调，两处都传它。
    // 原来两处 return@面板名只是提前退出本回调，这里改成开关没开就不做，
    // 和原来等价；开关开着时的增减名单与提示一字不改
    val onIptvFavoriteToggle: (Iptv) -> Unit = { iptv ->
        if (settingsViewModel.iptvChannelFavoriteEnable) {
            if (settingsViewModel.iptvChannelFavoriteList.contains(iptv.channelName)) {
                settingsViewModel.iptvChannelFavoriteList -= iptv.channelName
                LeanbackToastState.I.showToast("取消收藏: ${iptv.name}")
            } else {
                settingsViewModel.iptvChannelFavoriteList += iptv.channelName
                LeanbackToastState.I.showToast("已收藏: ${iptv.name}")
            }
        }
    }

    // 换台与切线路只写一份共用：原来遥控上下左右键和手势上下左右滑各写了
    // 一份一模一样的换台/切线路逻辑（共 8 处），改一处容易漏另一处；现在
    // 抽成四个共用的回调，键和手势都调它。方向对应（上键与下滑同向、下键
    // 与上滑同向、左键与右滑同向、右键与左滑同向）和翻转设置一字不改；
    // 翻转开关在每次按键时现读，不是定义时记死，设置里改了立即生效
    val moveChannelUp: () -> Unit = {
        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToNext()
        else mainContentState.changeCurrentIptvToPrev()
    }
    val moveChannelDown: () -> Unit = {
        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToPrev()
        else mainContentState.changeCurrentIptvToNext()
    }
    val moveUrlIdxPrev: () -> Unit = {
        if (mainContentState.currentIptv.urlList.size > 1) {
            mainContentState.changeCurrentIptv(
                iptv = mainContentState.currentIptv,
                urlIdx = mainContentState.currentIptvUrlIdx - 1,
            )
        }
    }
    val moveUrlIdxNext: () -> Unit = {
        if (mainContentState.currentIptv.urlList.size > 1) {
            mainContentState.changeCurrentIptv(
                iptv = mainContentState.currentIptv,
                urlIdx = mainContentState.currentIptvUrlIdx + 1,
            )
        }
    }

    // 播放界面是否正持有焦点（含子节点）：只在失焦时才补请求焦点，
    // 不再每 100ms 都调一次 requestFocus（已聚焦时是空操作）；循环
    // 本身保留作重试——焦点可能在动画中途抢不到，下轮再试才能粘住
    var videoScreenHasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        // 只在前台可见时抢焦点：App 退到后台后组合还在，原先的
        // LaunchedEffect(Unit) 会一直每 100ms 调一次 requestFocus，
        // 白白唤醒主线程；退后台时窗口本来就没焦点，抢了也没用，
        // 回到前台时循环恢复、重新抢回来
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // P4：失焦驱动的有界重试，不再每 100ms 永久轮询：焦点或弹窗
            // 状态一变才重新评估；丢焦且无弹窗时重试最多 10 次（100ms 起、
            // 指数退避到 500ms），拿到焦点即停，弹窗开关也会重新触发重试
            snapshotFlow {
                videoScreenHasFocus to Triple(
                    mainContentState.isPanelVisible,
                    mainContentState.isSettingsVisible,
                    mainContentState.isQuickPanelVisible,
                )
            }.collectLatest { (hasFocus, panels) ->
                if (hasFocus || panels.first || panels.second || panels.third) return@collectLatest
                var delayMs = 100L
                repeat(10) {
                    if (videoScreenHasFocus) return@collectLatest
                    focusRequester.requestFocus()
                    delay(delayMs)
                    delayMs = min(delayMs * 2, 500L)
                }
            }
        }
    }

    LeanbackBackPressHandledArea(
        modifier = modifier,
        onBackPressed = {
            if (mainContentState.isPanelVisible) mainContentState.isPanelVisible = false
            else if (mainContentState.isSettingsVisible) mainContentState.isSettingsVisible = false
            else if (mainContentState.isQuickPanelVisible) mainContentState.isQuickPanelVisible =
                false
            else onBackPressed()
        },
    ) {
        LeanbackVideoScreen(
            state = videoPlayerState,
            showMetadataProvider = { settingsViewModel.debugShowVideoPlayerMetadata },
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { videoScreenHasFocus = it.hasFocus }
                .handleLeanbackKeyEvents(
                    onUp = { moveChannelUp() },
                    onDown = { moveChannelDown() },
                    onLeft = { moveUrlIdxPrev() },
                    onRight = { moveUrlIdxNext() },
                    onSelect = { mainContentState.isPanelVisible = true },
                    onLongSelect = { mainContentState.isQuickPanelVisible = true },
                    onSettings = { mainContentState.isQuickPanelVisible = true },
                    onNumber = {
                        if (settingsViewModel.iptvChannelNoSelectEnable) {
                            panelChannelNoSelectState.input(it)
                        }
                    },
                    onLongDown = { mainContentState.isQuickPanelVisible = true },
                )
                .handleLeanbackDragGestures(
                    onSwipeDown = { moveChannelUp() },
                    onSwipeUp = { moveChannelDown() },
                    onSwipeRight = { moveUrlIdxPrev() },
                    onSwipeLeft = { moveUrlIdxNext() },
                ),
        )

        CompositionLocalProvider(
            LocalDensity provides Density(
                density = LocalDensity.current.density * settingsViewModel.uiDensityScaleRatio,
                fontScale = LocalDensity.current.fontScale * settingsViewModel.uiFontScaleRatio,
            )
        ) {
            LeanbackVisible({
                !mainContentState.isTempPanelVisible
                        && !mainContentState.isSettingsVisible
                        && !mainContentState.isPanelVisible
                        && !mainContentState.isQuickPanelVisible
                        && panelChannelNoSelectState.channelNo.isEmpty()
            }) {
                LeanbackPanelDateTimeScreen(
                    showModeProvider = { settingsViewModel.uiTimeShowMode }
                )
            }

            LeanbackPanelChannelNoSelectScreen(
                channelNoProvider = { panelChannelNoSelectState.channelNo }
            )

            LeanbackVisible({
                mainContentState.isTempPanelVisible
                        && !mainContentState.isSettingsVisible
                        && !mainContentState.isPanelVisible
                        && !mainContentState.isQuickPanelVisible
                        && panelChannelNoSelectState.channelNo.isEmpty()
            }) {
                LeanbackPanelTempScreen(
                    channelNoProvider = { iptvGroupList.channelNoText(mainContentState.currentIptv) },
                    currentIptvProvider = { mainContentState.currentIptv },
                    currentIptvUrlIdxProvider = { mainContentState.currentIptvUrlIdx },
                    currentProgrammesProvider = { epgList.currentProgrammes(mainContentState.currentIptv) },
                    showProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
                )
            }

            LeanbackVisible({ !settingsViewModel.uiUseClassicPanelScreen && mainContentState.isPanelVisible }) {
                LeanbackPanelScreen(
                    iptvGroupListProvider = { iptvGroupList },
                    epgListProvider = { epgList },
                    currentIptvProvider = { mainContentState.currentIptv },
                    currentIptvUrlIdxProvider = { mainContentState.currentIptvUrlIdx },
                    videoPlayerMetadataProvider = { videoPlayerState.metadata },
                    showProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
                    onIptvSelected = { mainContentState.changeCurrentIptv(it) },
                    onIptvFavoriteToggle = onIptvFavoriteToggle,
                    iptvFavoriteListProvider = { iptvFavoriteImmutableList },
                    iptvFavoriteListVisibleProvider = { settingsViewModel.iptvChannelFavoriteListVisible },
                    onIptvFavoriteListVisibleChange = {
                        settingsViewModel.iptvChannelFavoriteListVisible = it
                    },
                    onClose = { mainContentState.isPanelVisible = false },
                    iptvFavoriteEnableProvider = { settingsViewModel.iptvChannelFavoriteEnable }
                )
            }

            LeanbackVisible({ settingsViewModel.uiUseClassicPanelScreen && mainContentState.isPanelVisible }) {
                LeanbackClassicPanelScreen(
                    iptvGroupListProvider = { iptvGroupList },
                    epgListProvider = { epgList },
                    currentIptvProvider = { mainContentState.currentIptv },
                    showProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
                    onIptvSelected = { mainContentState.changeCurrentIptv(it) },
                    onIptvFavoriteToggle = onIptvFavoriteToggle,
                    iptvFavoriteListProvider = { iptvFavoriteImmutableList },
                    iptvFavoriteListVisibleProvider = { settingsViewModel.iptvChannelFavoriteListVisible },
                    onIptvFavoriteListVisibleChange = {
                        settingsViewModel.iptvChannelFavoriteListVisible = it
                    },
                    onClose = { mainContentState.isPanelVisible = false },
                    iptvFavoriteEnableProvider = { settingsViewModel.iptvChannelFavoriteEnable }
                )
            }
        }

        LeanbackVisible({ mainContentState.isQuickPanelVisible && !mainContentState.isSettingsVisible }) {
            LeanbackQuickPanelScreen(
                currentIptvProvider = { mainContentState.currentIptv },
                currentIptvUrlIdxProvider = { mainContentState.currentIptvUrlIdx },
                currentProgrammesProvider = { epgList.currentProgrammes(mainContentState.currentIptv) },
                currentIptvChannelNoProvider = {
                    iptvGroupList.channelNoText(mainContentState.currentIptv)
                },
                videoPlayerMetadataProvider = { videoPlayerState.metadata },
                videoPlayerAspectRatioProvider = { settingsViewModel.videoPlayerAspectRatio },
                onChangeVideoPlayerAspectRatio = { mode ->
                    // 画面比例按设置里的档位传，不再拿浮点数反推是哪一档：
                    // 16:9 的电视上“自动拉伸”和 16:9 是同一个数，4:3 的屏幕
                    // 上和 4:3 是同一个数，靠比数认档位会认错、甚至在 4:3
                    // 屏幕上按到 4:3 就卡住回不去；档位存好后按档位重算比例
                    settingsViewModel.videoPlayerAspectRatio = mode
                    videoPlayerState.applyDefaultAspectRatio()
                },
                onIptvUrlIdxChange = {
                    // 点中的就是正在播的那条线时不切：否则原样再播一遍，
                    // 画面黑一下重新缓冲；断流重备不走这里，不受影响
                    if (shouldSwitchUrlIdx(mainContentState.currentIptvUrlIdx, it)) {
                        mainContentState.changeCurrentIptv(
                            iptv = mainContentState.currentIptv,
                            urlIdx = it,
                        )
                    }
                },
                onClearCache = {
                    // 只写 SP 不够：可播放域名还有一份内存镜像在主界面状态里，
                    // 不刷新就一直留着，下次播起来会把旧的写回 SP——走
                    // clearPlayableHosts() 把镜像一并丢掉
                    mainContentState.clearPlayableHosts()
                    coroutineScope.launch {
                        // 只清目录内容，不删目录本身：AppGlobal.cacheDir 是
                        // 启动时取到的 File 引用，目录删了之后再写缓存会抛
                        // 错、只能重启恢复；留着目录，下次刷新直接重新拉取
                        // 已下载好还没装的更新包（latest.apk）别清：没给
                        // 安装权限时它会留在缓存里等装，误删后要重新下载
                        // 才能装；局域网刚上传的安装包（uploaded_apk.apk）
                        // 也别清：上传完只是发了安装请求，系统安装器要用户
                        // 在电视上手动确认，确认前文件必须还在，误删后安装
                        // 器读不到文件、得重新从手机上传。上传中途断开留
                        // 下的临时残文件（uploaded_apk_*.apk）照常清
                        AppGlobal.cacheDir.listFiles()?.forEach {
                            if (it.name != "latest.apk" && it.name != "uploaded_apk.apk") {
                                it.deleteRecursively()
                            }
                        }
                    }
                    LeanbackToastState.I.showToast("缓存已清除")
                },
                onMoreSettings = { mainContentState.isSettingsVisible = true },
                onClose = { mainContentState.isQuickPanelVisible = false },
            )
        }

        LeanbackVisible({ mainContentState.isSettingsVisible }) {
            LeanbackSettingsScreen()
        }

        LeanbackVisible({ settingsViewModel.debugShowFps }) {
            LeanbackMonitorScreen()
        }

        LeanbackUpdateScreen()
    }
}

@Composable
fun LeanbackBackPressHandledArea(
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = Box(
    modifier = Modifier
        .onPreviewKeyEvent {
            if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                // 返回回调同样包兜底：主界面每次按返回都走这里，回调里偶发
                // 异常不该让整个应用闪退；抛异常也照样消费，不漏下去被当成退出
                safeLeanbackInvoke(onBackPressed)
                true
            } else {
                false
            }
        }
        .then(modifier),
    content = content,
)