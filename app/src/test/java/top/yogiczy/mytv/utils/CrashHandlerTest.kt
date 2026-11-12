package top.yogiczy.mytv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashHandlerTest {
    @Test
    fun `报告里包含版本设备线程与异常信息`() {
        val report = CrashHandler.formatCrashReport(
            threadName = "main",
            throwable = IndexOutOfBoundsException("Index 3 out of bounds"),
            timestamp = 1794000000000L,
            appVersion = "2.2.14",
            androidVersion = "14",
            androidSdk = 34,
            deviceModel = "HUAWEI MatePad",
        )

        assertTrue(report.contains("2.2.14"))
        assertTrue(report.contains("HUAWEI MatePad"))
        assertTrue(report.contains("Android 14 (SDK 34)"))
        assertTrue(report.contains("main"))
        assertTrue(report.contains("IndexOutOfBoundsException"))
        assertTrue(report.contains("Index 3 out of bounds"))
        // 堆栈里至少有本测试类的帧
        assertTrue(report.contains("CrashHandlerTest"))
    }

    @Test
    fun `异常没有消息时也不崩`() {
        val report = CrashHandler.formatCrashReport(
            threadName = "DefaultDispatcher-worker-1",
            throwable = NullPointerException(),
            timestamp = 0L,
            appVersion = "2.2.14",
            androidVersion = "?",
            androidSdk = 0,
            deviceModel = "?",
        )

        assertTrue(report.contains("NullPointerException"))
        assertTrue(report.contains("DefaultDispatcher-worker-1"))
    }

    @Test
    fun `超长报告会被截断`() {
        // 造一条很深的堆栈：递归抛异常
        fun recurse(depth: Int): Nothing {
            if (depth <= 0) throw RuntimeException("deep")
            return recurse(depth - 1)
        }
        val throwable = try {
            recurse(500)
            RuntimeException("unreachable")
        } catch (e: RuntimeException) {
            e
        }

        val report = CrashHandler.formatCrashReport(
            threadName = "main",
            throwable = throwable,
            timestamp = 0L,
            appVersion = "2.2.14",
            androidVersion = "?",
            androidSdk = 0,
            deviceModel = "?",
        )

        assertTrue(report.length <= 16_000)
        assertTrue(report.startsWith("天光云影闪退报告"))
    }

    @Test
    fun `没有上次报告时返回null`() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        try {
            assertNull(CrashHandler.readPreviousCrashReport(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `读到上次报告后文件被删掉只汇报一次`() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        try {
            File(dir, CrashHandler.CRASH_LOG_FILE_NAME).writeText("fake crash report")

            assertEquals("fake crash report", CrashHandler.readPreviousCrashReport(dir))
            assertFalse(File(dir, CrashHandler.CRASH_LOG_FILE_NAME).exists())
            assertNull(CrashHandler.readPreviousCrashReport(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `空文件按没有报告处理`() {
        val dir = Files.createTempDirectory("crash-test").toFile()
        try {
            File(dir, CrashHandler.CRASH_LOG_FILE_NAME).writeText("   ")

            assertNull(CrashHandler.readPreviousCrashReport(dir))
            assertFalse(File(dir, CrashHandler.CRASH_LOG_FILE_NAME).exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
