package top.yogiczy.mytv.ui.screens.leanback.video.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.Constants

class VideoPlayerUserAgentTest {

    @Test
    fun `默认播放器 UA 不能含 ExoPlayer`() {
        // 凤凰三个台（fh/fhzx、fh/fhzixun、fh/fhhk）走腾讯云函数中转到
        // fengshows 的 FLV 流：上游会拦截 UA 里含 ExoPlayer 的请求，
        // 改发一个 HTML 下载页（share.fengshows.com/download.html），
        // 播放器拿到的就不是 FLV 而是 HTML，解封装失败、三个台都播不了；
        // 浏览器/Dalvik UA 则正常拿到 FLV 流。默认 UA 绝不能再含 ExoPlayer
        assertFalse(
            "默认播放器 UA 含 ExoPlayer，上游会改发 HTML 下载页、凤凰台播不了",
            Constants.VIDEO_PLAYER_USER_AGENT.contains("ExoPlayer", ignoreCase = true),
        )
    }

    @Test
    fun `默认播放器 UA 是浏览器 UA`() {
        // 服务器最普遍接受的是浏览器 UA；默认用它，各家源都不容易拦
        assertTrue(
            "默认播放器 UA 应当是浏览器 UA",
            Constants.VIDEO_PLAYER_USER_AGENT.contains("Mozilla", ignoreCase = true),
        )
        assertTrue(
            "默认播放器 UA 不能为空",
            Constants.VIDEO_PLAYER_USER_AGENT.isNotBlank(),
        )
    }
}
