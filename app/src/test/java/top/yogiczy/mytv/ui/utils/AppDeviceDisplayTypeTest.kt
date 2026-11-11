package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设备显示类型：第 176 轮把设置页“应用”里写的那份中文名 when
 * （TV / Pad / 手机）收到了档位自己身上（label），和时间显示、
 * 画面比例两个档位同一个做法，这里验收进来的值和原来写死的一模一样；
 * 存盘值和声明顺序没动，老数据读出来还是同一档。
 */
class AppDeviceDisplayTypeTest {
    @Test
    fun `labels match the old when branches in settings UI`() {
        assertEquals("TV", SP.AppDeviceDisplayType.LEANBACK.label)
        assertEquals("手机", SP.AppDeviceDisplayType.MOBILE.label)
        assertEquals("Pad", SP.AppDeviceDisplayType.PAD.label)
    }

    @Test
    fun `stored values are unchanged`() {
        assertEquals(0, SP.AppDeviceDisplayType.LEANBACK.value)
        assertEquals(1, SP.AppDeviceDisplayType.MOBILE.value)
        assertEquals(2, SP.AppDeviceDisplayType.PAD.value)
    }
}
