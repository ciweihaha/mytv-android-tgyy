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
import kotlinx.coroutines.launch
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.EpgList
import top.yogiczy.mytv.data.entities.EpgList.Companion.currentProgrammes
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvIdx
import top.yogiczy.mytv.data.entities.IptvGroupList.Companion.iptvList
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
    val panelChannelNoSelectState = rememberLeanbackPanelChannelNoSelectState(
        onChannelNoConfirm = {
            val channelNo = it.toInt() - 1

            if (channelNo in iptvGroupList.iptvList.indices) {
                mainContentState.changeCurrentIptv(iptvGroupList.iptvList[channelNo])
            }
        }
    )

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
            // 防止切换到其他界面时焦点丢失
            while (true) {
                if (!videoScreenHasFocus
                    && !mainContentState.isPanelVisible
                    && !mainContentState.isSettingsVisible
                    && !mainContentState.isQuickPanelVisible
                ) {
                    focusRequester.requestFocus()
                }
                delay(100)
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
                    onUp = {
                        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToNext()
                        else mainContentState.changeCurrentIptvToPrev()
                    },
                    onDown = {
                        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToPrev()
                        else mainContentState.changeCurrentIptvToNext()
                    },
                    onLeft = {
                        if (mainContentState.currentIptv.urlList.size > 1) {
                            mainContentState.changeCurrentIptv(
                                iptv = mainContentState.currentIptv,
                                urlIdx = mainContentState.currentIptvUrlIdx - 1,
                            )
                        }
                    },
                    onRight = {
                        if (mainContentState.currentIptv.urlList.size > 1) {
                            mainContentState.changeCurrentIptv(
                                iptv = mainContentState.currentIptv,
                                urlIdx = mainContentState.currentIptvUrlIdx + 1,
                            )
                        }
                    },
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
                    onSwipeDown = {
                        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToNext()
                        else mainContentState.changeCurrentIptvToPrev()
                    },
                    onSwipeUp = {
                        if (settingsViewModel.iptvChannelChangeFlip) mainContentState.changeCurrentIptvToPrev()
                        else mainContentState.changeCurrentIptvToNext()
                    },
                    onSwipeRight = {
                        if (mainContentState.currentIptv.urlList.size > 1) {
                            mainContentState.changeCurrentIptv(
                                iptv = mainContentState.currentIptv,
                                urlIdx = mainContentState.currentIptvUrlIdx - 1,
                            )
                        }
                    },
                    onSwipeLeft = {
                        if (mainContentState.currentIptv.urlList.size > 1) {
                            mainContentState.changeCurrentIptv(
                                iptv = mainContentState.currentIptv,
                                urlIdx = mainContentState.currentIptvUrlIdx + 1,
                            )
                        }
                    },
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
                    channelNoProvider = { iptvGroupList.iptvIdx(mainContentState.currentIptv) + 1 },
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
                    onIptvFavoriteToggle = {
                        if (!settingsViewModel.iptvChannelFavoriteEnable) return@LeanbackPanelScreen

                        if (settingsViewModel.iptvChannelFavoriteList.contains(it.channelName)) {
                            settingsViewModel.iptvChannelFavoriteList -= it.channelName
                            LeanbackToastState.I.showToast("取消收藏: ${it.channelName}")
                        } else {
                            settingsViewModel.iptvChannelFavoriteList += it.channelName
                            LeanbackToastState.I.showToast("已收藏: ${it.channelName}")
                        }
                    },
                    iptvFavoriteListProvider = { settingsViewModel.iptvChannelFavoriteList.toImmutableList() },
                    iptvFavoriteListVisibleProvider = { settingsViewModel.iptvChannelFavoriteListVisible },
                    onIptvFavoriteListVisibleChange = {
                        settingsViewModel.iptvChannelFavoriteListVisible = it
                    },
                    onClose = { mainContentState.isPanelVisible = false },
                )
            }

            LeanbackVisible({ settingsViewModel.uiUseClassicPanelScreen && mainContentState.isPanelVisible }) {
                LeanbackClassicPanelScreen(
                    iptvGroupListProvider = { iptvGroupList },
                    epgListProvider = { epgList },
                    currentIptvProvider = { mainContentState.currentIptv },
                    showProgrammeProgressProvider = { settingsViewModel.uiShowEpgProgrammeProgress },
                    onIptvSelected = { mainContentState.changeCurrentIptv(it) },
                    onIptvFavoriteToggle = {
                        if (!settingsViewModel.iptvChannelFavoriteEnable) return@LeanbackClassicPanelScreen

                        if (settingsViewModel.iptvChannelFavoriteList.contains(it.channelName)) {
                            settingsViewModel.iptvChannelFavoriteList -= it.channelName
                            LeanbackToastState.I.showToast("取消收藏: ${it.channelName}")
                        } else {
                            settingsViewModel.iptvChannelFavoriteList += it.channelName
                            LeanbackToastState.I.showToast("已收藏: ${it.channelName}")
                        }
                    },
                    iptvFavoriteListProvider = { settingsViewModel.iptvChannelFavoriteList.toImmutableList() },
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
                    (iptvGroupList.iptvIdx(mainContentState.currentIptv) + 1).toString()
                        .padStart(2, '0')
                },
                videoPlayerMetadataProvider = { videoPlayerState.metadata },
                videoPlayerAspectRatioProvider = { videoPlayerState.aspectRatio },
                onChangeVideoPlayerAspectRatio = {
                    videoPlayerState.aspectRatio = it
                    // 同步写回设置里的画面比例：否则下次收到分辨率事件
                    // （切台、断线重连）时 onResolution 会按设置重算，
                    // 快捷面板刚选的比例就悄悄丢了；未知值只改本次，不写设置
                    when (it) {
                        16f / 9f -> settingsViewModel.videoPlayerAspectRatio =
                            SP.VideoPlayerAspectRatio.SIXTEEN_NINE

                        4f / 3f -> settingsViewModel.videoPlayerAspectRatio =
                            SP.VideoPlayerAspectRatio.FOUR_THREE

                        configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat() ->
                            settingsViewModel.videoPlayerAspectRatio =
                                SP.VideoPlayerAspectRatio.AUTO
                    }
                },
                onIptvUrlIdxChange = {
                    mainContentState.changeCurrentIptv(
                        iptv = mainContentState.currentIptv,
                        urlIdx = it,
                    )
                },
                onClearCache = {
                    settingsViewModel.iptvPlayableHostList = emptySet()
                    coroutineScope.launch {
                        // 只清目录内容，不删目录本身：AppGlobal.cacheDir 是
                        // 启动时取到的 File 引用，目录删了之后再写缓存会抛
                        // 错、只能重启恢复；留着目录，下次刷新直接重新拉取
                        AppGlobal.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
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
                onBackPressed()
                true
            } else {
                false
            }
        }
        .then(modifier),
    content = content,
)