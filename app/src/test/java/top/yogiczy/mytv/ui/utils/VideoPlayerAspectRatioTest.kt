package top.yogiczy.mytv.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面比例档位：第 165 轮把快捷面板和设置页各写一份的中文名与
 * 循环顺序收到了档位自己身上（label / next()），这里验收进来的
 * 值和原来两处写死的一模一样。
 */
class VideoPlayerAspectRatioTest {
    @Test
    fun `labels match the old duplicated when branches`() {
        assertEquals("原始", SP.VideoPlayerAspectRatio.ORIGINAL.label)
        assertEquals("16:9", SP.VideoPlayerAspectRatio.SIXTEEN_NINE.label)
        assertEquals("4:3", SP.VideoPlayerAspectRatio.FOUR_THREE.label)
        assertEquals("自动拉伸", SP.VideoPlayerAspectRatio.AUTO.label)
    }

    @Test
    fun `next cycles in declaration order and wraps around`() {
        assertEquals(
            SP.VideoPlayerAspectRatio.SIXTEEN_NINE,
            SP.VideoPlayerAspectRatio.ORIGINAL.next()
        )
        assertEquals(
            SP.VideoPlayerAspectRatio.FOUR_THREE,
            SP.VideoPlayerAspectRatio.SIXTEEN_NINE.next()
        )
        assertEquals(
            SP.VideoPlayerAspectRatio.AUTO,
            SP.VideoPlayerAspectRatio.FOUR_THREE.next()
        )
        assertEquals(
            SP.VideoPlayerAspectRatio.ORIGINAL,
            SP.VideoPlayerAspectRatio.AUTO.next()
        )
    }

    @Test
    fun `stored values are unchanged`() {
        assertEquals(0, SP.VideoPlayerAspectRatio.ORIGINAL.value)
        assertEquals(1, SP.VideoPlayerAspectRatio.SIXTEEN_NINE.value)
        assertEquals(2, SP.VideoPlayerAspectRatio.FOUR_THREE.value)
        assertEquals(3, SP.VideoPlayerAspectRatio.AUTO.value)
    }
}
