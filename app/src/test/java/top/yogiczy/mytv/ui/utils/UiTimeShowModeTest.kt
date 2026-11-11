package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时间显示模式：第 166 轮把设置页“界面”里写的那份短名字 when
 * 和旁边按 entries 下标现算的换档，收到了模式自己身上（label /
 * next()），这里验收进来的值和原来写死的一模一样。
 */
class UiTimeShowModeTest {
    @Test
    fun `labels match the old when branches in settings UI`() {
        assertEquals("隐藏", SP.UiTimeShowMode.HIDDEN.label)
        assertEquals("常显", SP.UiTimeShowMode.ALWAYS.label)
        assertEquals("整点", SP.UiTimeShowMode.EVERY_HOUR.label)
        assertEquals("半点", SP.UiTimeShowMode.HALF_HOUR.label)
    }

    @Test
    fun `next cycles in declaration order and wraps around`() {
        assertEquals(
            SP.UiTimeShowMode.ALWAYS,
            SP.UiTimeShowMode.HIDDEN.next()
        )
        assertEquals(
            SP.UiTimeShowMode.EVERY_HOUR,
            SP.UiTimeShowMode.ALWAYS.next()
        )
        assertEquals(
            SP.UiTimeShowMode.HALF_HOUR,
            SP.UiTimeShowMode.EVERY_HOUR.next()
        )
        assertEquals(
            SP.UiTimeShowMode.HIDDEN,
            SP.UiTimeShowMode.HALF_HOUR.next()
        )
    }

    @Test
    fun `stored values are unchanged`() {
        assertEquals(0, SP.UiTimeShowMode.HIDDEN.value)
        assertEquals(1, SP.UiTimeShowMode.ALWAYS.value)
        assertEquals(2, SP.UiTimeShowMode.EVERY_HOUR.value)
        assertEquals(3, SP.UiTimeShowMode.HALF_HOUR.value)
    }
}
