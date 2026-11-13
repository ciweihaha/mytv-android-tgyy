package top.yogiczy.mytv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃报告格式：OK 键闪退排查用的诊断日志，报告头四行固定字段，
 * 堆栈原样附后；格式变了，网页端 /api/crash 读到的内容就变了，这里锁死。
 */
class CrashHandlerTest {
    @Test
    fun `report contains header fields and stack trace`() {
        val report = buildCrashReport(
            time = "2026-11-12 21:00:00",
            threadName = "main",
            appVersion = "2.2.13",
            device = "HONOR LOK-360C (SDK 28)",
            stackTrace = "java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.kt:1)",
        )
        assertTrue(report.startsWith("time: 2026-11-12 21:00:00\n"))
        assertTrue(report.contains("thread: main\n"))
        assertTrue(report.contains("version: 2.2.13\n"))
        assertTrue(report.contains("device: HONOR LOK-360C (SDK 28)\n"))
        assertTrue(report.endsWith("at Foo.bar(Foo.kt:1)"))
    }
}
