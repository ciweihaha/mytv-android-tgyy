package top.yogiczy.mytv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanbackBackKeyPressCycleTrackerTest {
    @Test
    fun `一次按下加一次抬起算一次返回`() {
        val t = LeanbackBackKeyPressCycleTracker()
        assertTrue(t.onKeyDown())
        assertTrue(t.onKeyUp())
    }

    @Test
    fun `没有按下就来的抬起不算返回`() {
        val t = LeanbackBackKeyPressCycleTracker()
        // 杂散抬起、重复投递：没见过按下，直接丢掉
        assertFalse(t.onKeyUp())
        assertFalse(t.onKeyUp())
    }

    @Test
    fun `同一次物理按键拆出的第二起抬起不算返回`() {
        val t = LeanbackBackKeyPressCycleTracker()
        // 2.2.31 自退的根因场景：一次按键拆出两起抬起，第二起和
        // 第一起来得再久（≥300ms）也不能算第二次按压——300ms
        // 去重兜不住隔得久的重复投递，按压周期守卫必须兜住
        assertTrue(t.onKeyDown())
        assertTrue(t.onKeyUp())
        assertFalse(t.onKeyUp())
    }

    @Test
    fun `第二次完整按压周期照常算返回`() {
        val t = LeanbackBackKeyPressCycleTracker()
        assertTrue(t.onKeyDown())
        assertTrue(t.onKeyUp())
        assertTrue(t.onKeyDown())
        assertTrue(t.onKeyUp())
    }

    @Test
    fun `长按的重复按下不重新记周期`() {
        val t = LeanbackBackKeyPressCycleTracker()
        assertTrue(t.onKeyDown())
        // 长按重复按下（repeatCount > 0）：不接受为新的按下
        assertFalse(t.onKeyDown(repeatCount = 1))
        assertFalse(t.onKeyDown(repeatCount = 2))
        // 原先那次按下对应的抬起照常算一次返回
        assertTrue(t.onKeyUp())
        // 抬起消费掉周期后，没有新的按下，抬起不再算
        assertFalse(t.onKeyUp())
    }

    @Test
    fun `重复按下之后抬起只算一次`() {
        val t = LeanbackBackKeyPressCycleTracker()
        assertTrue(t.onKeyDown())
        assertFalse(t.onKeyDown(repeatCount = 1))
        assertTrue(t.onKeyUp())
        assertFalse(t.onKeyUp())
    }
}
