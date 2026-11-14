package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.Constants

class LoggerHistoryCapTest {
    @Test
    fun `超过上限时删最旧的只留50条`() {
        // 上限 50 条：连加 55 条后只剩最后 50 条，最早 5 条被删掉、
        // 流里也是同一份 50 条；截断若丢了，列表会一直长下去
        val stamp = System.nanoTime()
        val messages = (0 until 55).map { "截断-$stamp-$it" }
        messages.forEach {
            Logger.addHistoryItem(Logger.HistoryItem(Logger.LevelType.INFO, "Test", it))
        }

        val history = Logger.history
        assertEquals(Constants.LOG_HISTORY_MAX_SIZE, history.size)
        assertEquals(messages[5], history.first().message)
        messages.take(5).forEach { assertFalse(history.any { item -> item.message == it }) }
        assertTrue(history.any { it.message == messages.last() })

        val fromFlow = Logger.historyFlow.value
        assertEquals(Constants.LOG_HISTORY_MAX_SIZE, fromFlow.size)
        assertTrue(fromFlow.any { it.message == messages.last() })
    }
}
