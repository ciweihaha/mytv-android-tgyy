package top.yogiczy.mytv.ui.screens.leanback.video.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3ContainerRetryTest {
    @Test
    fun `没试过先试 HLS`() {
        assertEquals(C.CONTENT_TYPE_HLS, nextUntriedContentType(emptyMap()))
    }

    @Test
    fun `HLS 试过改试普通容器`() {
        assertEquals(
            C.CONTENT_TYPE_OTHER,
            nextUntriedContentType(mapOf(C.CONTENT_TYPE_HLS to true)),
        )
    }

    @Test
    fun `HLS 和普通容器试过改试 RTSP`() {
        assertEquals(
            C.CONTENT_TYPE_RTSP,
            nextUntriedContentType(
                mapOf(C.CONTENT_TYPE_HLS to true, C.CONTENT_TYPE_OTHER to true),
            ),
        )
    }

    @Test
    fun `三种都试过返回空、上层上报错误`() {
        assertNull(
            nextUntriedContentType(
                mapOf(
                    C.CONTENT_TYPE_HLS to true,
                    C.CONTENT_TYPE_OTHER to true,
                    C.CONTENT_TYPE_RTSP to true,
                ),
            ),
        )
    }

    @Test
    fun `记成没试过也算没试过`() {
        assertEquals(
            C.CONTENT_TYPE_HLS,
            nextUntriedContentType(mapOf(C.CONTENT_TYPE_HLS to false)),
        )
    }
}
