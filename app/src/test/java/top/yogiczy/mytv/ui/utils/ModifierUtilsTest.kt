package top.yogiczy.mytv.ui.utils

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierUtilsTest {
    @Test
    fun `只传 onSelect 时点按表只有三个确定键`() {
        var called = 0
        val onSelect = { called++ }
        val map = leanbackKeyTapMap(onSelect = onSelect, sdkInt = 24)

        assertEquals(
            setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
            ),
            map.keys,
        )

        // 三个键指向同一个回调
        map[KeyEvent.KEYCODE_DPAD_CENTER]?.invoke()
        map[KeyEvent.KEYCODE_ENTER]?.invoke()
        map[KeyEvent.KEYCODE_NUMPAD_ENTER]?.invoke()
        assertEquals(3, called)
    }

    @Test
    fun `没传回调时两张表都空`() {
        assertTrue(leanbackKeyTapMap(sdkInt = 24).isEmpty())
        assertTrue(leanbackKeyLongTapMap().isEmpty())
    }

    @Test
    fun `没传的键不进表_事件继续往下传`() {
        val map = leanbackKeyTapMap(onSelect = {}, sdkInt = 24)

        // 方向键、菜单键、数字键都没传，不在表里
        assertFalse(map.containsKey(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(map.containsKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(map.containsKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(map.containsKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(map.containsKey(KeyEvent.KEYCODE_MENU))
        assertFalse(map.containsKey(KeyEvent.KEYCODE_0))
    }

    @Test
    fun `系统导航键只在 API25 以上注册`() {
        val below = leanbackKeyTapMap(onUp = {}, sdkInt = 24)
        assertFalse(below.containsKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP))

        val above = leanbackKeyTapMap(onUp = {}, sdkInt = 25)
        assertTrue(above.containsKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP))
        assertTrue(above.containsKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(above.containsKey(KeyEvent.KEYCODE_CHANNEL_UP))
    }

    @Test
    fun `键盘 L 键走长按确定_长按表只收确定键`() {
        val tapMap = leanbackKeyTapMap(onLongSelect = {}, sdkInt = 24)
        assertEquals(setOf(KeyEvent.KEYCODE_L), tapMap.keys)

        val longMap = leanbackKeyLongTapMap(onLongSelect = {})
        assertEquals(
            setOf(
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
            ),
            longMap.keys,
        )
    }

    @Test
    fun `数字键按对应数字回调`() {
        val numbers = mutableListOf<Int>()
        val map = leanbackKeyTapMap(onNumber = { numbers += it }, sdkInt = 24)

        map[KeyEvent.KEYCODE_0]?.invoke()
        map[KeyEvent.KEYCODE_3]?.invoke()
        map[KeyEvent.KEYCODE_9]?.invoke()
        assertEquals(listOf(0, 3, 9), numbers)
    }
}
