package top.yogiczy.mytv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 平板主题：与手机主题完全一致（系统深浅色 + 动态取色），只留一个
 * 名字转调 MobileTheme。原来两份文件除函数名外一字不差，各改各的
 * 容易改漏一边；以后平板真要区分再把这里展开
 */
@Composable
fun PadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    MobileTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content,
    )
}
