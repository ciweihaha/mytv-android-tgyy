package top.yogiczy.mytv.ui.screens.leanback.main.components

import androidx.media3.common.PlaybackException as Media3PlaybackException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import top.yogiczy.mytv.ui.screens.leanback.video.player.LeanbackVideoPlayer

class MainContentAutoRetuneTest {
    @Test
    fun `2004 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ),
        )
    }

    @Test
    fun `2001 和 2002 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ),
        )
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ),
        )
    }

    @Test
    fun `BEHIND_LIVE_WINDOW 耗尽后自动重调`() {
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            ),
        )
    }

    @Test
    fun `解码错不自动重调`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            ),
        )
    }

    @Test
    fun `解析错不自动重调——PARSING_MANIFEST_MALFORMED 不在重试之列`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                Media3PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            ),
        )
    }

    @Test
    fun `加载超时耗尽后自动重调——冷启动第一次拉流慢撞上看门狗`() {
        // 看门狗 30 秒一到直接上报，播放器不做内部重试；冷启动第
        // 一次拉流慢（SCF 302 可达 10 秒 + 首个分片慢）很容易撞
        // 上，不重调就挂死、用户只能退出重进。重调一次（那时网络
        // 已热）大多能播起来；同一轮失败只重调一次，再失败就报错
        assertTrue(
            shouldAutoRetuneAfterExhaustion(
                LeanbackVideoPlayer.PlaybackException.LOAD_TIMEOUT.errorCode,
            ),
        )
    }

    @Test
    fun `断流不自动重调`() {
        assertFalse(
            shouldAutoRetuneAfterExhaustion(
                LeanbackVideoPlayer.PlaybackException.CUTOFF.errorCode,
            ),
        )
    }

    @Test
    fun `没有错误码时不自动重调`() {
        assertFalse(shouldAutoRetuneAfterExhaustion(null))
    }

    @Test
    fun `自动重调等 5 秒、不超过 10 秒上限`() {
        assertEquals(5000L, LeanbackMainContentState.AUTO_RETUNE_DELAY_MS)
        assertEquals(10000L, LeanbackMainContentState.AUTO_RETUNE_MAX_WAIT_MS)
        assertTrue(
            LeanbackMainContentState.AUTO_RETUNE_DELAY_MS <=
                LeanbackMainContentState.AUTO_RETUNE_MAX_WAIT_MS,
        )
    }

    // ---- 重解析：旧链接过期 → 新列表里找同台同线的新地址 ----

    @Test
    fun `重解析命中同名频道同线的新地址`() {
        val current = Iptv(
            name = "河南卫视",
            urlList = listOf("https://scf.example/ysp/henanws.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视",
                                urlList = listOf("https://scf.example/ysp/henanws.m3u8?v=2"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "https://scf.example/ysp/henanws.m3u8?v=2",
            resolveFreshChannelUrl(current, 0, fresh),
        )
    }

    @Test
    fun `重解析按 channelName 兜底命中`() {
        val current = Iptv(
            name = "河南卫视",
            channelName = "henanws",
            urlList = listOf("https://scf.example/old.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视高清",
                                channelName = "henanws",
                                urlList = listOf("https://scf.example/new.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "https://scf.example/new.m3u8",
            resolveFreshChannelUrl(current, 0, fresh),
        )
    }

    @Test
    fun `重解析新地址和旧地址一样也返回——河南卫视 SCF 地址重拉不变`() {
        val current = Iptv(
            name = "河南卫视",
            urlList = listOf("https://scf.example/ysp/henanws.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视",
                                urlList = listOf("https://scf.example/ysp/henanws.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "https://scf.example/ysp/henanws.m3u8",
            resolveFreshChannelUrl(current, 0, fresh),
        )
    }

    @Test
    fun `重解析找不到频道返回 null——走旧地址重调`() {
        val current = Iptv(
            name = "河南卫视",
            urlList = listOf("https://scf.example/old.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(name = "江苏卫视", urlList = listOf("https://x.example/a.m3u8")),
                        ),
                    ),
                ),
            ),
        )
        assertNull(resolveFreshChannelUrl(current, 0, fresh))
    }

    @Test
    fun `重解析下标越界返回 null——新列表里这台的线变少了`() {
        val current = Iptv(
            name = "河南卫视",
            urlList = listOf(
                "https://scf.example/line0.m3u8",
                "https://scf.example/line1.m3u8",
            ),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视",
                                urlList = listOf("https://scf.example/line0.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertNull(resolveFreshChannelUrl(current, 1, fresh))
    }

    @Test
    fun `重解析同名频道先认同组——别组同名台不抢线`() {
        val current = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf("https://old.example/cctv1-a.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "央视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "CCTV-1",
                                channelName = "cctv1",
                                urlList = listOf("https://a.example/cctv1.m3u8"),
                            ),
                        ),
                    ),
                ),
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "CCTV-1",
                                channelName = "cctv1",
                                urlList = listOf("https://b.example/cctv1.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        // 不传分组名是老行为：全表 firstOrNull 拿到央视那条
        assertEquals("https://a.example/cctv1.m3u8", resolveFreshChannelUrl(current, 0, fresh))
        // 传了分组名就只在同组里找，不会被别组同名台抢走
        assertEquals(
            "https://b.example/cctv1.m3u8",
            resolveFreshChannelUrl(current, 0, fresh, "卫视"),
        )
        assertEquals(
            "https://a.example/cctv1.m3u8",
            resolveFreshChannelUrl(current, 0, fresh, "央视"),
        )
    }

    @Test
    fun `重解析分组名不在新列表里回退全局`() {
        val current = Iptv(
            name = "CCTV-1",
            channelName = "cctv1",
            urlList = listOf("https://old.example/cctv1.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "其他",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "CCTV-1",
                                channelName = "cctv1",
                                urlList = listOf("https://x.example/cctv1.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "https://x.example/cctv1.m3u8",
            resolveFreshChannelUrl(current, 0, fresh, "央视"),
        )
    }

    @Test
    fun `重解析组内按 channelName 兜底——不越组找同名台`() {
        val current = Iptv(
            name = "河南卫视",
            channelName = "henanws",
            urlList = listOf("https://old.example/henanws.m3u8"),
        )
        val fresh = IptvGroupList(
            listOf(
                IptvGroup(
                    name = "央视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视",
                                channelName = "other",
                                urlList = listOf("https://a.example/henanws.m3u8"),
                            ),
                        ),
                    ),
                ),
                IptvGroup(
                    name = "卫视",
                    iptvList = IptvList(
                        listOf(
                            Iptv(
                                name = "河南卫视高清",
                                channelName = "henanws",
                                urlList = listOf("https://b.example/henanws.m3u8"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "https://b.example/henanws.m3u8",
            resolveFreshChannelUrl(current, 0, fresh, "卫视"),
        )
    }

    @Test
    fun `retuneUrl 有新地址用新地址`() {
        assertEquals(
            "https://scf.example/new.m3u8",
            retuneUrl("https://scf.example/old.m3u8", "https://scf.example/new.m3u8"),
        )
    }

    @Test
    fun `retuneUrl 解析失败用旧地址——null 和空串都回退`() {
        assertEquals(
            "https://scf.example/old.m3u8",
            retuneUrl("https://scf.example/old.m3u8", null),
        )
        assertEquals(
            "https://scf.example/old.m3u8",
            retuneUrl("https://scf.example/old.m3u8", ""),
        )
    }

    // ---- 重解析：取消信号原样抛出，不吞掉 ----

    @Test
    fun `重解析解析中取消原样抛出——不吞 CancellationException`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val deferred = async {
            defaultResolveFreshChannelUrl(
                Iptv(name = "河南卫视", urlList = listOf("https://old.example/a.m3u8")),
                0,
                fetch = {
                    entered.complete(Unit)
                    delay(Long.MAX_VALUE)
                    IptvGroupList(emptyList())
                },
            )
        }
        entered.await()
        deferred.cancel()
        try {
            deferred.await()
            fail("取消信号被吞了：应抛 CancellationException 而不是返回 null")
        } catch (_: CancellationException) {
            // 预期：原样抛出
        }
    }

    @Test
    fun `重解析普通异常返回 null——取消除外`() = runBlocking {
        assertNull(
            defaultResolveFreshChannelUrl(
                Iptv(name = "河南卫视", urlList = listOf("https://old.example/a.m3u8")),
                0,
                fetch = { throw RuntimeException("拉取失败") },
            ),
        )
    }
}
