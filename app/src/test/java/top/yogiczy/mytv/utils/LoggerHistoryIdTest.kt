package top.yogiczy.mytv.utils

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerHistoryIdTest {
    @Test
    fun `同一毫秒两条一样的日志入库后编号不同`() {
        // 同一毫秒、同一标签、同一内容：原来列表拿这三样当唯一键会撞键崩掉
        val time = System.currentTimeMillis()
        val first = Logger.HistoryItem(Logger.LevelType.ERROR, "Test", "刷新失败", time = time)
        val second = Logger.HistoryItem(Logger.LevelType.ERROR, "Test", "刷新失败", time = time)
        Logger.addHistoryItem(first)
        Logger.addHistoryItem(second)

        val history = Logger.history
        val last = history[history.size - 1]
        val secondLast = history[history.size - 2]
        assertTrue(last.id > 0)
        assertTrue(secondLast.id > 0)
        assertNotEquals(secondLast.id, last.id)
    }
}
