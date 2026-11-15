package top.yogiczy.mytv.ui.screens.leanback.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.utils.Logger

class SettingsCategoryLogTest {
    @Test
    fun `log level letter covers four levels`() {
        assertEquals("D", logLevelLetter(Logger.LevelType.DEBUG))
        assertEquals("I", logLevelLetter(Logger.LevelType.INFO))
        assertEquals("W", logLevelLetter(Logger.LevelType.WARN))
        assertEquals("E", logLevelLetter(Logger.LevelType.ERROR))
    }

    @Test
    fun `log supporting text appends cause on a new line`() {
        assertEquals("出错了\njava.lang.Exception: 401", logSupportingText("出错了", "java.lang.Exception: 401"))
        // 没有原因时就是消息本身，不留尾巴（壳线同款函数是空格分隔、没原因留一个空格，天光这边是换行分隔、没原因不留换行）
        assertEquals("出错了", logSupportingText("出错了", null))
        // 原因是个空串时现行写法仍会加一个换行，原样钉死
        assertEquals("出错了\n", logSupportingText("出错了", ""))
    }
}
