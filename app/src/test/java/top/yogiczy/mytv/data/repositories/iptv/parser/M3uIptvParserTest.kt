package top.yogiczy.mytv.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uIptvParserTest {
    private val parser = M3uIptvParser()

    @Test
    fun `isSupport accepts EXT-M3U playlists only`() {
        assertTrue(parser.isSupport("https://example.com/list.m3u", "#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", "央视,#genre#\nCCTV-1,http://example.com/1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", ""))
    }

    @Test
    fun `parses channels grouped by group-title`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("卫视", result[1].name)
        assertEquals("湖南卫视", result[1].iptvList[0].name)
    }

    @Test
    fun `missing group-title falls back to 其他`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1,无分组频道
            http://example.com/nogroup.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("无分组频道", result[0].iptvList[0].name)
    }

    @Test
    fun `blank group-title falls back to 其他`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title=" ",CCTV-1
            http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }

    @Test
    fun `semicolon in group-title is not split into groups`() = runBlocking {
        // group-title 里的 `;` 不切分：三个真实源（默认源 56 台、主单 69 条、
        // 合并单 102 条）里一个带 `;` 的 group-title 都没有，没证据改语义；
        // 这里只钉死现有行为——整个串就是一个分组名
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视;卫视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视;卫视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视;卫视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `duplicate channel names merge into one entry with multiple urls`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1-b.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals(
            listOf("http://example.com/cctv1-a.m3u8", "http://example.com/cctv1-b.m3u8"),
            result[0].iptvList[0].urlList,
        )
    }

    @Test
    fun `handles CRLF line endings`() = runBlocking {
        val data = "#EXTM3U\r\n#EXTINF:-1 group-title=\"央视\",CCTV-1\r\nhttp://example.com/cctv1.m3u8\r\n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `isSupport accepts BOM-prefixed and leading-blank-line playlists`() {
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\uFEFF#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\n\n#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\uFEFF\n#EXTM3U\n#EXTINF:-1,CCTV-1"))
        // 壳 826 轮姊妹检查：反复转存、拼接过的源开头可能带两个 BOM，
        // 或 BOM 在空行后面——isSupport 是 indexOfFirst 跳过全部前导
        // BOM/空白，本来就认，但这两档一次也没断言过
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\uFEFF\uFEFF#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertTrue(parser.isSupport("https://example.com/list.m3u", "\n\uFEFF#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/list.m3u", "\uFEFF央视,#genre#\nCCTV-1,http://example.com/1"))
    }

    @Test
    fun `parses BOM-prefixed playlist normally`() = runBlocking {
        val data = "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\nhttp://example.com/cctv1.m3u8\n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `parses double-BOM playlist normally`() = runBlocking {
        // 壳 826 轮姊妹检查：双 BOM 开头，isSupport 那档已断言认得出，
        // 解析整条走下来也要不断言——首行是带双 BOM 的 #EXTM3U，
        // 不是 #EXTINF 行，直接跳过，频道照常解析
        val data = "\uFEFF\uFEFF#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\nhttp://example.com/cctv1.m3u8\n#EXTINF:-1 tvg-name=\"CCTV-5\" group-title=\"央视\",CCTV-5\nhttp://example.com/cctv5.m3u8\n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `trailing EXTINF without url line does not crash and is skipped`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-5
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }

    @Test
    fun `isSupport accepts lowercase extm3u header`() {
        assertTrue(parser.isSupport("https://example.com/list.m3u", "#extm3u\n#EXTINF:-1,CCTV-1"))
    }

    @Test
    fun `parses indented EXTINF lines`() = runBlocking {
        val data = """
            #EXTM3U
              #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `parses lowercase extinf lines`() = runBlocking {
        val data = """
            #EXTM3U
            #extinf:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `EXTINF without display name falls back to tvg-name or is skipped`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视"
            http://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视"
            http://example.com/noname.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `channel name containing comma is kept whole`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1,高清
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-logo="http://example.com/a,b.png" group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1,高清", result[0].iptvList[0].name)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `parses tvg-id for epg lookup and keeps channel name unchanged`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV综合" group-title="央视频道",CCTV综合
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="凤凰中文" group-title="其他地区直播",凤凰中文
            http://example.com/fhzx
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("cctv1", result[0].iptvList[0].tvgId)
        assertEquals("CCTV综合", result[0].iptvList[0].channelName)
        assertEquals("", result[1].iptvList[0].tvgId)
        assertEquals("凤凰中文", result[1].iptvList[0].channelName)
    }

    @Test
    fun `mixed-case attribute names are recognized`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 TVG-ID="cctv1" TVG-NAME="CCTV-1" GROUP-TITLE="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 Tvg-Name="CCTV-5" Group-Title="央视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("cctv1", result[0].iptvList[0].tvgId)
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
        assertEquals("CCTV-5", result[0].iptvList[1].channelName)
    }

    @Test
    fun `blank lines between EXTINF and url do not drop the channel`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1

            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5


            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
        assertEquals(listOf("http://example.com/cctv5.m3u8"), result[0].iptvList[1].urlList)
    }

    @Test
    fun `EXTINF followed only by blank lines and the next EXTINF is still skipped`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 group-title="央视",CCTV-1

            #EXTINF:-1 group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-5", result[0].iptvList[0].name)
    }

    @Test
    fun `duplicate attribute keeps the first value`() = runBlocking {
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" tvg-name="CCTV-1B" group-title="央视",显示名
            http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("显示名", result[0].iptvList[0].name)
        // 先到先得：与原来各正则只取第一次匹配的语义一致
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
    }

    @Test
    fun `last channel without trailing newline is still parsed`() = runBlocking {
        val data = "#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\nhttp://example.com/cctv1.m3u8"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `display name empty after comma falls back to tvg-name or is skipped`() = runBlocking {
        // 逗号后是空的：trim 后取不到非空显示名，回落到 tvg-name；
        // 没有 tvg-name 的那条跳过——与“没逗号”那档走同一条 ?: 链，
        // 但 trim 后为空这一步之前没有任何用例钉过
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",
            http://example.com/cctv1.m3u8
            #EXTINF:-1 group-title="央视",
            http://example.com/noname.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].channelName)
    }

    @Test
    fun `duplicate display name keeps the first lines channelName and tvgId`() = runBlocking {
        // 同名频道多行并进一条时地址都留着，但 channelName 与 tvgId
        // 取第一条的：之前那档重复行元数据全相同，分不清先赢还是后赢
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV综合" group-title="央视",CCTV-1
            http://example.com/cctv1-a.m3u8
            #EXTINF:-1 tvg-id="cctv1-hd" tvg-name="CCTV-1 高清" group-title="央视",CCTV-1
            http://example.com/cctv1-b.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV综合", result[0].iptvList[0].channelName)
        assertEquals("cctv1", result[0].iptvList[0].tvgId)
        assertEquals(
            listOf("http://example.com/cctv1-a.m3u8", "http://example.com/cctv1-b.m3u8"),
            result[0].iptvList[0].urlList,
        )
    }

    @Test
    fun `duplicate urls collapse and same name in different groups stays separate`() = runBlocking {
        // 壳 811 轮回镜的后半边：同名两行地址相同只留一路（.distinct()
        // 一次也没钉过）、同名频道分属两组各留一份；先赢那半边
        // 1932 轮已做。壳 812 轮是 1932 轮的端到端版——天光的 parse
        // 本身就是端到端（解析与分组同一层），不用另补
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV综合" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv1-hd" tvg-name="CCTV-1 高清" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-id="cctv1-ws" tvg-name="CCTV-1" group-title="卫视",CCTV-1
            http://example.com/cctv1-ws.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV综合", result[0].iptvList[0].channelName)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("卫视", result[1].name)
        assertEquals(1, result[1].iptvList.size)
        assertEquals("CCTV-1", result[1].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1-ws.m3u8"), result[1].iptvList[0].urlList)
    }

    @Test
    fun `empty input parses to empty list`() = runBlocking {
        // 壳 818 轮姊妹检查的后半边（M3u 那半）：空源解析出空列表、不崩；
        // isSupport 空串那档已有用例，parse("") 出空列表一次也没断言过
        assertTrue(parser.parse("").isEmpty())
    }

    @Test
    fun `unbalanced quote in EXTINF skips the channel`() = runBlocking {
        // 壳 886 轮姊妹检查：引号没闭合的残缺行——逗号落在没闭合的
        // 引号里，substringAfterFirstUnquotedComma 取不到；tvg-name
        // 正则也要闭合引号才匹配得到，两边都没有就整条跳过；后面那
        // 行地址也不能误填到上一条好频道上
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV-9,CCTV-10
            http://example.com/garbage.m3u8
            #EXTINF:-1 tvg-name="CCTV-2" group-title="央视",CCTV-2
            http://example.com/cctv2.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("CCTV-2", result[0].iptvList[1].name)
        assertEquals(listOf("http://example.com/cctv2.m3u8"), result[0].iptvList[1].urlList)
        assertTrue(result[0].iptvList.none { it.urlList.contains("http://example.com/garbage.m3u8") })
    }

    @Test
    fun `url line with surrounding whitespace is trimmed`() = runBlocking {
        // 地址行首尾带空白：手改文件常在地址后留空格；scan 循环里
        // candidate 先 trim 再存，不去掉的话地址带空格播不了——
        // 这档一次也没断言过
        val data = "#EXTM3U\n#EXTINF:-1 tvg-name=\"CCTV-1\" group-title=\"央视\",CCTV-1\n  http://example.com/cctv1.m3u8  \n"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `interleaved groups merge into first-appearance order`() = runBlocking {
        // 分组交错出现：拼接过的源常把同一分组写在两处；groupBy 按
        // 首次出现顺序拼组，后出现的同名分组并进前面那组、频道按
        // 出现顺序排——这档之前只测过分组整块连续的源
        val data = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
            #EXTINF:-1 tvg-name="CCTV-5" group-title="央视",CCTV-5
            http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
        assertEquals("卫视", result[1].name)
        assertEquals(1, result[1].iptvList.size)
        assertEquals("湖南卫视", result[1].iptvList[0].name)
    }
}
