package top.yogiczy.mytv.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节目单按 tvg-id 兜底查：默认直播源和默认节目单是同一批台，但名字写法
 * 不一样（直播源“CCTV综合”、节目单“CCTV-1 综合”），只按名查时 19 个台
 * 显示“无节目”；tvg-id 和节目单 channel 的 id 是同一套，按 id 能找回。
 * 用例里的名字和 id 取自真实默认源（live.m3u / epg.xml，2026-11-11 核对）。
 */
class EpgListTest {
    private fun liveEpg(channel: String, id: String = ""): Epg {
        val now = System.currentTimeMillis()
        return Epg(
            channel = channel,
            id = id,
            programmes = EpgProgrammeList(
                listOf(
                    EpgProgramme(
                        startAt = now - 1800_000,
                        endAt = now + 1800_000,
                        title = "正在播",
                    ),
                )
            ),
        )
    }

    @Test
    fun `name match still wins`() {
        val epgList = EpgList(listOf(liveEpg("湖南卫视", "hnws")))
        val iptv = Iptv(name = "湖南卫视", channelName = "湖南卫视", tvgId = "hnws")

        assertEquals("正在播", epgList.currentProgrammes(iptv)?.now?.title)
        assertEquals("湖南卫视", epgList.epgFor(iptv)?.channel)
    }

    @Test
    fun `falls back to tvg-id when names differ in spelling`() {
        val epgList = EpgList(listOf(liveEpg("CCTV-1 综合", "cctv1")))
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv1")

        assertEquals("正在播", epgList.currentProgrammes(iptv)?.now?.title)
        assertEquals("CCTV-1 综合", epgList.epgFor(iptv)?.channel)
    }

    @Test
    fun `id match with unrelated names is rejected`() {
        // 上游把海南卫视的 id 写成了湖南卫视备的 id（hnws2）：只按 id 认会
        // 把湖南卫视备的节目单贴到海南卫视上；名字对不上就宁可不贴
        val epgList = EpgList(listOf(liveEpg("湖南卫视备", "hnws2")))
        val iptv = Iptv(name = "海南卫视", channelName = "海南卫视", tvgId = "hnws2")

        assertNull(epgList.epgFor(iptv))
        assertNull(epgList.currentProgrammes(iptv))
    }

    @Test
    fun `id match with only digits differing is rejected`() {
        // 节目单里 "CCTV-4K 超高清"（cctv4k）和 "CCTV-8K 超高清"（cctv8k）
        // 去掉数字后都变成 "cctvk超高清"：只按去数字后的名字复核会把 4K 的
        // 节目单贴到 8K 上；数字串 4 对 8 对不上就宁可不贴
        val epgList = EpgList(listOf(liveEpg("CCTV-4K 超高清", "cctv8k")))
        val iptv = Iptv(name = "CCTV8K超高清", channelName = "CCTV8K超高清", tvgId = "cctv8k")

        assertNull(epgList.epgFor(iptv))
        assertNull(epgList.currentProgrammes(iptv))
    }

    @Test
    fun `returns null when channel has no tvg-id and name does not match`() {
        val epgList = EpgList(listOf(liveEpg("CCTV-1 综合", "cctv1")))
        val iptv = Iptv(name = "凤凰中文", channelName = "凤凰中文", tvgId = "")

        assertNull(epgList.epgFor(iptv))
    }

    @Test
    fun `returns null for unknown tvg-id`() {
        val epgList = EpgList(listOf(liveEpg("CCTV-1 综合", "cctv1")))
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv99")

        assertNull(epgList.epgFor(iptv))
    }

    @Test
    fun `gap between programmes still shows the next one`() {
        // 节目空档（上一档播完、下一档还没开始）时，原先直接回 null，
        // 面板里“正在播放”和“稍后播放”都显示“无节目”；现在把最近
        // 的下一个节目带上，正在播放仍是空
        val now = System.currentTimeMillis()
        val epgList = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    id = "cctv1",
                    programmes = EpgProgrammeList(
                        listOf(
                            EpgProgramme(
                                startAt = now - 7200_000,
                                endAt = now - 3600_000,
                                title = "上一档",
                            ),
                            EpgProgramme(
                                startAt = now + 3600_000,
                                endAt = now + 7200_000,
                                title = "下一档",
                            ),
                        )
                    ),
                )
            )
        )
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv1")

        val current = epgList.currentProgrammes(iptv)
        assertNull(current?.now)
        assertEquals("下一档", current?.next?.title)
    }

    @Test
    fun `before first programme of the day still shows the next one`() {
        val now = System.currentTimeMillis()
        val epgList = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    id = "cctv1",
                    programmes = EpgProgrammeList(
                        listOf(
                            EpgProgramme(
                                startAt = now + 3600_000,
                                endAt = now + 7200_000,
                                title = "首档",
                            ),
                        )
                    ),
                )
            )
        )
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv1")

        val current = epgList.currentProgrammes(iptv)
        assertNull(current?.now)
        assertEquals("首档", current?.next?.title)
    }

    @Test
    fun `returns null when all programmes have ended`() {
        val now = System.currentTimeMillis()
        val epgList = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    id = "cctv1",
                    programmes = EpgProgrammeList(
                        listOf(
                            EpgProgramme(
                                startAt = now - 7200_000,
                                endAt = now - 3600_000,
                                title = "已播完",
                            ),
                        )
                    ),
                )
            )
        )
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv1")

        assertNull(epgList.currentProgrammes(iptv))
    }

    @Test
    fun `next programme does not depend on list order`() {
        // 节目单顺序不保证按时间排：列表里先写晚的、后写早的，最近的
        // 下一个仍应是开始时间最早的那档，而不是列表里的第一档
        val now = System.currentTimeMillis()
        val epgList = EpgList(
            listOf(
                Epg(
                    channel = "CCTV-1 综合",
                    id = "cctv1",
                    programmes = EpgProgrammeList(
                        listOf(
                            EpgProgramme(
                                startAt = now + 7200_000,
                                endAt = now + 10800_000,
                                title = "晚一档",
                            ),
                            EpgProgramme(
                                startAt = now + 3600_000,
                                endAt = now + 7200_000,
                                title = "早一档",
                            ),
                        )
                    ),
                )
            )
        )
        val iptv = Iptv(name = "CCTV综合", channelName = "CCTV综合", tvgId = "cctv1")

        val current = epgList.currentProgrammes(iptv)
        assertNull(current?.now)
        assertEquals("早一档", current?.next?.title)
    }

    @Test
    fun `name spelling check ignores digits spaces dash and plus`() {
        assertTrue(isSameEpgChannelName("CCTV综合", "CCTV-1 综合"))
        assertTrue(isSameEpgChannelName("CCTV体育赛事", "CCTV-5+ 体育赛事"))
        assertTrue(isSameEpgChannelName("CCTV8K超高清", "CCTV-8K 超高清"))
        assertTrue(isSameEpgChannelName("湖南卫视", "湖南卫视"))
        assertFalse(isSameEpgChannelName("海南卫视", "湖南卫视备"))
        assertFalse(isSameEpgChannelName("CCTV综合", "CCTV财经"))
        assertFalse(isSameEpgChannelName("1", "2"))
        // 只差数字的台：4K 和 8K 去数字后名字一模一样，数字串对不上就不能算同一个台
        assertFalse(isSameEpgChannelName("CCTV8K超高清", "CCTV-4K 超高清"))
    }
}
