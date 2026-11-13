package top.yogiczy.mytv.ui.screens.leanback.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.QrShapes
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import top.yogiczy.mytv.ui.utils.swallowUnhandledSelectKeys

@Composable
fun LeanbackQrcode(
    modifier: Modifier = Modifier,
    text: String,
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.onBackground,
                shape = MaterialTheme.shapes.medium,
            )
    ) {
        Image(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .padding(10.dp),
            painter = rememberQrCodePainter(
                data = text,
                shapes = QrShapes(
                    ball = QrBallShape.circle(),
                    darkPixel = QrPixelShape.roundCorners(),
                    frame = QrFrameShape.roundCorners(.25f),
                ),
            ),
            contentDescription = text,
        )
    }
}

@Composable
fun LeanbackQrcodeDialog(
    modifier: Modifier = Modifier,
    textProvider: () -> String = { "" },
    description: String? = null,
    showDialogProvider: () -> Boolean = { false },
    onDismissRequest: () -> Unit = {},
) {
    if (showDialogProvider()) {
        AlertDialog(
            modifier = modifier.swallowUnhandledSelectKeys(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = onDismissRequest,
            confirmButton = { description?.let { Text(text = description) } },
            text = {
                LeanbackQrcode(
                    // 二维码文字只在弹窗打开时才取：原来是调用方每次组合就
                    // 把字符串算好传进来，设置页每个列表项都挂着一个这样
                    // 的弹窗（平时根本不打开），每按一次遥控挪焦点、每
                    // 个列表项重组一次，就要把本机网卡枚举一遍现算一次
                    // 局域网地址；改成打开时才取，IP 会变时现算的语义
                    // 不变（每次打开都重新取），平时不再白算
                    text = textProvider(),
                    modifier = Modifier
                        .width(240.dp)
                        .height(240.dp),
                )
            },
        )
    }
}