package top.yogiczy.mytv.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerHistoryFlowTest {
    @Test
    fun `入库后流里立刻能拿到这条日志`() {
        // 日志页原来只读一次快照：停在日志页上，后入库的日志一直不出现。
        // 流在入库时同步更新，读流就能拿到刚入库的那条
        val message = "流里能看到-${System.nanoTime()}"
        Logger.addHistoryItem(Logger.HistoryItem(Logger.LevelType.INFO, "Test", message))

        val history = Logger.historyFlow.value
        assertTrue(history.any { it.tag == "Test" && it.message == message && it.id > 0 })
    }

    @Test
    fun `流里的内容和快照一致`() {
        val message = "流与快照一致-${System.nanoTime()}"
        Logger.addHistoryItem(Logger.HistoryItem(Logger.LevelType.WARN, "Test", message))

        val fromFlow = Logger.historyFlow.value
        val fromSnapshot = Logger.history
        assertTrue(fromFlow.size == fromSnapshot.size)
        assertTrue(fromFlow.any { it.message == message })
        assertTrue(fromSnapshot.any { it.message == message })
    }
}
