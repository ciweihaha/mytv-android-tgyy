package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierUtilsSafeInvokeTest {
    @Test
    fun `正常回调照常执行`() {
        var called = 0
        safeLeanbackInvoke { called++ }
        assertEquals(1, called)
    }

    @Test
    fun `回调抛异常时不扩散`() {
        var afterInvoke = false
        // 抛异常也不应让调用方崩掉：这行跑完，后面的代码还能继续
        safeLeanbackInvoke { throw IndexOutOfBoundsException("test") }
        afterInvoke = true
        assertTrue(afterInvoke)
    }

    @Test
    fun `回调抛空指针异常时也不扩散`() {
        safeLeanbackInvoke { throw NullPointerException("test") }
        // 能跑到这里就说明异常被接住了
        assertTrue(true)
    }
}
