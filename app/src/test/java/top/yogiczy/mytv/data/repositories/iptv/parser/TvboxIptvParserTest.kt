package top.yogiczy.mytv.data.repositories.iptv.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvboxIptvParserTest {
    private val parser = TvboxIptvParser()

    @Test
    fun `isSupport accepts tvbox format only`() {
        assertTrue(parser.isSupport("https://example.com/tv.txt", "央视,#genre#\nCCTV-1,http://example.com/1"))
        assertFalse(parser.isSupport("https://example.com/tv.txt", "#EXTM3U\n#EXTINF:-1,CCTV-1"))
        assertFalse(parser.isSupport("https://example.com/tv.txt", ""))
    }

    @Test
    fun `parses channels grouped by genre`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1.m3u8
            CCTV-5,http://example.com/cctv5.m3u8
            卫视,#genre#
            湖南卫视,http://example.com/hunan.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("卫视", result[1].name)
        assertEquals("湖南卫视", result[1].iptvList[0].name)
    }

    @Test
    fun `multiple urls separated by hash merge into one entry`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1-a.m3u8#http://example.com/cctv1-b.m3u8
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
    fun `chinese comma is treated as separator`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1，http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `channels before any genre fall back to 其他 and blank lines are skipped`() = runBlocking {
        val data = """
            CCTV-1,http://example.com/cctv1.m3u8

            # a comment line
            央视,#genre#
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("央视", result[1].name)
    }

    @Test
    fun `empty url segments are dropped`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1.m3u8#
            CCTV-2,
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `url containing genre marker is parsed as channel not group`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/a#genre#
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("http://example.com/a", result[0].iptvList[0].urlList[0])
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `fragment segment is dropped when a real url exists`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/a#genre#
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals(listOf("http://example.com/a"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `scheme-less url is kept when it is the only segment`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,192.168.1.1:8080/live
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(listOf("192.168.1.1:8080/live"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `chinese comma genre line is recognized as group`() = runBlocking {
        val data = """
            央视，#genre#
            CCTV-1,http://example.com/cctv1.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }

    @Test
    fun `nameless lines are skipped`() = runBlocking {
        val data = """
            央视,#genre#
            ,http://example.com/nameless.m3u8
            CCTV-1,http://example.com/cctv1.m3u8
            ,http://example.com/nameless2.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }

    @Test
    fun `url containing comma is kept intact`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/live.m3u8?token=a,b
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(
            listOf("http://example.com/live.m3u8?token=a,b"),
            result[0].iptvList[0].urlList,
        )
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `url containing comma still splits multiple urls on hash`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/a.m3u8?token=x,y#http://example.com/b.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(1, result[0].iptvList.size)
        assertEquals(
            listOf(
                "http://example.com/a.m3u8?token=x,y",
                "http://example.com/b.m3u8",
            ),
            result[0].iptvList[0].urlList,
        )
    }

    @Test
    fun `nameless genre line does not reset group name`() = runBlocking {
        // 无名分组行不能把分组名写成空串：后面的频道仍进原来的分组，
        // 而不是进一个无名假分组
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1.m3u8
            ,#genre#
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `line without comma is skipped`() = runBlocking {
        val data = """
            央视,#genre#
            CCTV-1,http://example.com/cctv1.m3u8
            CCTV-9
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `degenerate genre line first falls back to other group`() = runBlocking {
        // 壳 818 轮姊妹检查：退化分组行 `#genre#,#genre#` 在文件第一行：
        // 去掉两个标记后分组名为空，前面还没有分组可保留，频道应进
        // “其他”，不能出现空白分组名、也不能崩。天光这边这行以 # 开头
        // 先被当注释跳过（`,#genre#` 那半边守卫走不到），结果同一档
        val data = "#genre#,#genre#\nCCTV-1,http://example.com/cctv1"

        assertTrue(parser.isSupport("", data))
        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("其他", result[0].name)
        assertEquals(1, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1"), result[0].iptvList[0].urlList)
    }

    @Test
    fun `empty input parses to empty list`() = runBlocking {
        // 壳 818 轮姊妹检查的后半边（Tvbox 那半）：空源解析出空列表、不崩；
        // isSupport 空串那档已有用例，parse("") 出空列表一次也没断言过
        assertTrue(parser.parse("").isEmpty())
    }

    @Test
    fun `spaces after comma in genre and channel lines are tolerated`() = runBlocking {
        // 分组行与频道行逗号后带空格：手改文件常写成 `央视, #genre#`；
        // 分组判断那路先 trimStart、地址那路先 trim，这两档一次也没断言过
        val data = """
            央视, #genre#
            CCTV-1, http://example.com/cctv1.m3u8
            CCTV-5,http://example.com/cctv5.m3u8
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(2, result[0].iptvList.size)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals(listOf("http://example.com/cctv1.m3u8"), result[0].iptvList[0].urlList)
        assertEquals("CCTV-5", result[0].iptvList[1].name)
    }

    @Test
    fun `leading BOM does not pollute first group name`() = runBlocking {
        // Windows 记事本存的 TXT 常带 UTF-8 BOM：不去掉的话首行分组名
        // 会带上一个看不见的 \uFEFF——trim() 去不掉它，分组名就和干
        // 净的“央视”对不上；M3U 那边 isSupport 已跳 BOM，这里入口
        // 统一去掉
        val data = "\uFEFF央视,#genre#\nCCTV-1,http://example.com/cctv1.m3u8"

        val result = parser.parse(data)

        assertEquals(1, result.size)
        assertEquals("央视", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
    }

    @Test
    fun `leading BOM before first channel does not pollute its name`() = runBlocking {
        // 频道在分组行之前时（回退到“其他”），BOM 在首个频道行上：
        // 同样在入口去掉，频道名干净、分组仍是“其他”
        val data = "\uFEFFCCTV-1,http://example.com/cctv1.m3u8\n央视,#genre#\nCCTV-5,http://example.com/cctv5.m3u8"

        val result = parser.parse(data)

        assertEquals(2, result.size)
        assertEquals("其他", result[0].name)
        assertEquals("CCTV-1", result[0].iptvList[0].name)
        assertEquals("央视", result[1].name)
        assertEquals("CCTV-5", result[1].iptvList[0].name)
    }
}
