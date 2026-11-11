package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 档位枚举共用：第 172 轮把 SP 里三份 fromValue（按存盘值找档位、
 * 找不到回默认值）和两份 next()（按声明顺序换下一档、到头回第一档）
 * 收到了工具文件里的两份共用小函数（storedEnumFromValue / nextEnum），
 * 这里验收：已知的存盘值找回来还是原来那一档，脏数据回落的默认值
 * 和原来一样，换档顺序和原来一样。
 */
class SpStoredEnumTest {
    @Test
    fun `fromValue returns the stored mode for known values`() {
        assertEquals(SP.UiTimeShowMode.HIDDEN, SP.UiTimeShowMode.fromValue(0))
        assertEquals(SP.UiTimeShowMode.ALWAYS, SP.UiTimeShowMode.fromValue(1))
        assertEquals(SP.UiTimeShowMode.EVERY_HOUR, SP.UiTimeShowMode.fromValue(2))
        assertEquals(SP.UiTimeShowMode.HALF_HOUR, SP.UiTimeShowMode.fromValue(3))

        assertEquals(SP.AppDeviceDisplayType.LEANBACK, SP.AppDeviceDisplayType.fromValue(0))
        assertEquals(SP.AppDeviceDisplayType.MOBILE, SP.AppDeviceDisplayType.fromValue(1))
        assertEquals(SP.AppDeviceDisplayType.PAD, SP.AppDeviceDisplayType.fromValue(2))

        assertEquals(SP.VideoPlayerAspectRatio.ORIGINAL, SP.VideoPlayerAspectRatio.fromValue(0))
        assertEquals(
            SP.VideoPlayerAspectRatio.SIXTEEN_NINE,
            SP.VideoPlayerAspectRatio.fromValue(1)
        )
        assertEquals(
            SP.VideoPlayerAspectRatio.FOUR_THREE,
            SP.VideoPlayerAspectRatio.fromValue(2)
        )
        assertEquals(SP.VideoPlayerAspectRatio.AUTO, SP.VideoPlayerAspectRatio.fromValue(3))
    }

    @Test
    fun `fromValue falls back to each enum's own default for unknown values`() {
        // 脏数据：以前不存在的值、负数，各回各的默认值，不串档
        assertEquals(SP.UiTimeShowMode.HIDDEN, SP.UiTimeShowMode.fromValue(99))
        assertEquals(SP.UiTimeShowMode.HIDDEN, SP.UiTimeShowMode.fromValue(-1))
        assertEquals(SP.AppDeviceDisplayType.LEANBACK, SP.AppDeviceDisplayType.fromValue(99))
        assertEquals(SP.AppDeviceDisplayType.LEANBACK, SP.AppDeviceDisplayType.fromValue(-1))
        assertEquals(SP.VideoPlayerAspectRatio.ORIGINAL, SP.VideoPlayerAspectRatio.fromValue(99))
        assertEquals(SP.VideoPlayerAspectRatio.ORIGINAL, SP.VideoPlayerAspectRatio.fromValue(-1))
    }

    @Test
    fun `next still cycles in declaration order and wraps around`() {
        assertEquals(SP.UiTimeShowMode.ALWAYS, SP.UiTimeShowMode.HIDDEN.next())
        assertEquals(SP.UiTimeShowMode.HIDDEN, SP.UiTimeShowMode.HALF_HOUR.next())
        assertEquals(
            SP.VideoPlayerAspectRatio.SIXTEEN_NINE,
            SP.VideoPlayerAspectRatio.ORIGINAL.next()
        )
        assertEquals(SP.VideoPlayerAspectRatio.ORIGINAL, SP.VideoPlayerAspectRatio.AUTO.next())
    }
}
