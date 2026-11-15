package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Test

private class DefaultTagLoggable : Loggable() {
    fun info(message: String) = log.i(message)
}

private class CustomTagLoggable : Loggable("CustomTag") {
    fun info(message: String) = log.i(message)
}

class LoggableTest {
    @Test
    fun `subclass without tag logs under its own class name`() {
        // 不传 tag 时标签是子类的类名：HttpServer、Downloader、
        // GitRepository 这些子类靠它在日志页里分清是谁记的；改成
        // 写死字符串时，这档会立刻红
        val message = "default-tag-${System.nanoTime()}"
        DefaultTagLoggable().info(message)

        val item = Logger.history.first { it.message == message }
        assertEquals("DefaultTagLoggable", item.tag)
    }

    @Test
    fun `explicit tag wins over class name`() {
        // 显式传了 tag 就用传进去的：子类名那档被覆盖掉，别两档串了
        val message = "custom-tag-${System.nanoTime()}"
        CustomTagLoggable().info(message)

        val item = Logger.history.first { it.message == message }
        assertEquals("CustomTag", item.tag)
    }
}
