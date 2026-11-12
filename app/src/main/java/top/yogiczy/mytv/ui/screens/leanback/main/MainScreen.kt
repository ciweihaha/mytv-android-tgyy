package top.yogiczy.mytv.ui.screens.leanback.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrShapes
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import top.yogiczy.mytv.ui.rememberLeanbackChildPadding
import top.yogiczy.mytv.ui.screens.leanback.components.LeanbackVisible
import top.yogiczy.mytv.ui.screens.leanback.main.components.LeanbackBackPressHandledArea
import top.yogiczy.mytv.ui.screens.leanback.main.components.LeanbackMainContent
import top.yogiczy.mytv.ui.screens.leanback.settings.LeanbackSettingsScreen
import top.yogiczy.mytv.ui.theme.LeanbackTheme
import top.yogiczy.mytv.ui.utils.HttpServer
import top.yogiczy.mytv.ui.utils.handleLeanbackKeyEvents

@Composable
fun LeanbackMainScreen(
    modifier: Modifier = Modifier,
    onBackPressed: () -> Unit = {},
    mainViewModel: LeanbackMainViewModel = viewModel(),
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    when (val s = uiState) {
        is LeanbackMainUiState.Ready -> LeanbackMainContent(
            modifier = modifier,
            iptvGroupList = s.iptvGroupList,
            epgList = s.epgList,
            onBackPressed = onBackPressed,
        )

        is LeanbackMainUiState.Loading -> LeanbackMainSettingsHandle(onBackPressed = onBackPressed) {
            LeanbackMainScreenLoading { s.message }
        }

        is LeanbackMainUiState.Error -> LeanbackMainSettingsHandle(
            onBackPressed = onBackPressed,
            // 加载失败后按确定键直接重试：原先只能进设置页清缓存或重选源，
            // 临时网络故障也要绕一大圈
            onRetry = { mainViewModel.refresh() },
        ) {
            LeanbackMainScreenError({ s.message })
        }
    }
}

@Composable
private fun LeanbackMainScreenLoading(messageProvider: () -> String?) {
    val childPadding = rememberLeanbackChildPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = childPadding.start, bottom = childPadding.bottom),
        ) {
            Text(
                text = "加载中...",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            val message = messageProvider()
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.sizeIn(maxWidth = 500.dp),
                )
            }
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackMainScreenLoadingPreview() {
    LeanbackTheme {
        LeanbackMainScreenLoading { "获取远程直播源(2/10)..." }
    }
}

@Composable
private fun LeanbackMainScreenError(
    messageProvider: () -> String?,
    serverUrl: String = HttpServer.serverUrl,
) {
    val childPadding = rememberLeanbackChildPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = childPadding.start, bottom = childPadding.bottom),
        ) {
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )

            val message = messageProvider()
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.sizeIn(maxWidth = 500.dp),
                )
            }

            Text(
                text = "按确定键重新加载",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = childPadding.end, bottom = childPadding.bottom)
                .width(100.dp)
                .height(100.dp)
                .background(
                    color = MaterialTheme.colorScheme.onBackground,
                    shape = MaterialTheme.shapes.medium,
                ),
        ) {
            Image(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                painter = rememberQrCodePainter(
                    data = serverUrl,
                    shapes = QrShapes(
                        ball = QrBallShape.circle(),
                        darkPixel = QrPixelShape.roundCorners(),
                        frame = QrFrameShape.roundCorners(.25f),
                    ),
                ),
                contentDescription = serverUrl,
            )
        }
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackMainScreenErrorPreview() {
    LeanbackTheme {
        LeanbackMainScreenError(
            { "获取远程直播源失败，请检查网络连接" },
            "http://244.178.44.111:8080",
        )
    }
}

@Preview(device = "id:Android TV (720p)")
@Composable
private fun LeanbackMainScreenErrorLongPreview() {
    LeanbackTheme {
        LeanbackMainScreenError(
            { "Caused by: androidx.media3.datasource.HttpDataSource\$HttpDataSourceException:" + " java.io.IOException: unexpected end of stream on com.android.okhttp.Address@2f10c24d" },
            "http://244.178.44.111:8080",
        )
    }
}

@Composable
private fun LeanbackMainSettingsHandle(
    modifier: Modifier = Modifier,
    onBackPressed: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var showSettings by remember { mutableStateOf(false) }

    // 设置页关掉后把焦点要回来：设置页打开时自己抢了焦点，关掉后焦
    // 点不会自己回来（和主界面那边循环补焦点一个道理）；只在关着的
    // 时候要，打开时设置页自己会抢，不抢它的
    LaunchedEffect(showSettings) {
        if (!showSettings) focusRequester.requestFocus()
    }

    // 返回键先关设置页、再走退出确认：和主界面那边（MainContent）
    // 同一个做法。原来直接走退出确认，加载/失败页上打开设置页后，
    // 返回键关不掉设置页——按一次弹“再按一次退出”、再按就直接退
    // 出应用，想回失败页按确定重试只能先退出重进
    LeanbackBackPressHandledArea(onBackPressed = {
        if (showSettings) showSettings = false else onBackPressed()
    }) {
        Box(
            modifier = modifier
                .focusRequester(focusRequester)
                .focusable()
                .handleLeanbackKeyEvents(
                    onSettings = {
                        showSettings = true
                    },
                    // 设置页打开时确定键归设置项：外层不注册 onSelect，
                    // 事件漏下去给设置项；注册了会在外层消费掉，设置项收不到
                    // （handleLeanbackKeyEvents 只收录传了回调的键）
                    onSelect = if (!showSettings) onRetry else null,
                ),
        ) {
            content()

            LeanbackVisible({ showSettings }) {
                LeanbackSettingsScreen()
            }
        }
    }
}