package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.isLive
import top.yogiczy.mytv.data.entities.EpgProgramme.Companion.progress

class EpgProgrammeTest {
    @Test
    fun `live programme progress is about halfway`() {
        val now = System.currentTimeMillis()
        val programme = EpgProgramme(
            startAt = now - 5_000,
            endAt = now + 5_000,
            title = "测试节目",
        )

        assertTrue(programme.isLive())
        val progress = programme.progress()
        assertTrue(progress > 0.4f && progress < 0.6f)
    }

    @Test
    fun `degenerate programme with zero length is not live and progress is 0`() {
        // 时间解析失败时 startAt/endAt 都记 0；EXAMPLE 预览数据也是如此
        val programme = EpgProgramme(startAt = 0, endAt = 0, title = "退化节目")

        assertFalse(programme.isLive())
        assertEquals(0f, programme.progress(), 0f)
    }

    @Test
    fun `programme with end before start is not live and progress is 0`() {
        val now = System.currentTimeMillis()
        val programme = EpgProgramme(startAt = now + 5_000, endAt = now - 5_000, title = "时间倒置")

        assertFalse(programme.isLive())
        assertEquals(0f, programme.progress(), 0f)
    }

    @Test
    fun `ended programme progress is clamped to 1`() {
        val now = System.currentTimeMillis()
        val programme = EpgProgramme(startAt = now - 10_000, endAt = now - 1_000, title = "已播完")

        assertFalse(programme.isLive())
        assertEquals(1f, programme.progress(), 0f)
    }

    @Test
    fun `not-started programme progress is clamped to 0`() {
        val now = System.currentTimeMillis()
        val programme = EpgProgramme(startAt = now + 10_000, endAt = now + 20_000, title = "未开始")

        assertFalse(programme.isLive())
        assertEquals(0f, programme.progress(), 0f)
    }
}
