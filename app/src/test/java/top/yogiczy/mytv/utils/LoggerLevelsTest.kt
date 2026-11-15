package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerLevelsTest {
    @Test
    fun `i w e record with their own levels`() {
        // 实例方法调 Log 的同时往历史里记一条：级别不能串，标签与
        // 内容要原样记下来
        val stamp = System.nanoTime()
        val infoMsg = "info-$stamp"
        val warnMsg = "warn-$stamp"
        val errorMsg = "error-$stamp"
        val log = Logger.create("Test")
        log.i(infoMsg)
        log.w(warnMsg)
        log.e(errorMsg)

        val history = Logger.history
        assertEquals(
            Logger.LevelType.INFO,
            history.first { it.message == infoMsg }.level
        )
        assertEquals(
            Logger.LevelType.WARN,
            history.first { it.message == warnMsg }.level
        )
        assertEquals(
            Logger.LevelType.ERROR,
            history.first { it.message == errorMsg }.level
        )
        assertEquals("Test", history.first { it.message == infoMsg }.tag)
    }

    @Test
    fun `wtf records as ERROR not a separate level`() {
        // LevelType 里没有 WTF：wtf 记成 ERROR，别因为级别对不上
        // 在日志页里被滤掉
        val message = "wtf-${System.nanoTime()}"
        Logger.create("Test").wtf(message)

        val item = Logger.history.first { it.message == message }
        assertEquals(Logger.LevelType.ERROR, item.level)
    }

    @Test
    fun `throwable message is recorded as cause`() {
        val message = "cause-${System.nanoTime()}"
        Logger.create("Test").e(message, RuntimeException("boom"))

        val item = Logger.history.first { it.message == message }
        assertEquals("boom", item.cause)
    }

    @Test
    fun `throwable without message records null cause`() {
        val message = "null-cause-${System.nanoTime()}"
        Logger.create("Test").w(message, RuntimeException())

        val item = Logger.history.first { it.message == message }
        assertNull(item.cause)
    }

    @Test
    fun `d does not record history`() {
        // DEBUG 不进历史是刻意的（d 里那行是注释掉的）：日志页只留
        // INFO 及以上，别有人看着注释以为是漏写给“补”回去
        val message = "debug-${System.nanoTime()}"
        Logger.create("Test").d(message)

        assertFalse(Logger.history.any { it.message == message })
        assertFalse(Logger.historyFlow.value.any { it.message == message })
    }
}
