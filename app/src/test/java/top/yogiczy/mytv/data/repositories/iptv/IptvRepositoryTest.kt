package top.yogiczy.mytv.data.repositories.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList
import java.io.File

class IptvRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    @Test
    fun `getIptvGroupList simplify end to end keeps cctv and satellite only`() = runBlocking {
        // getIptvGroupList 本身一次也没端到端断言过：simplifyTest 与
        // checkIptvGroupListNotEmpty 只在单元层测过，方法里的精简映射
        // （按台名过滤）与空分组丢弃（过滤后没台的分组整组去掉）都没
        // 走通过——映射里丢了 filter 空分组那行、没台的分组会漏进界面。
        // 缓存预先写好、有效期给足，地址故意写 127.0.0.1:9：缓存命中
        // 时一下也不联网，走了刷新去下载立刻连接被拒、用例失败
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV-1" group-title="央视",CCTV-1
            http://example.com/cctv1.m3u8
            #EXTINF:-1 tvg-name="湖南卫视" group-title="卫视",湖南卫视
            http://example.com/hunan.m3u8
            #EXTINF:-1 tvg-name="BBC 新闻" group-title="国际",BBC 新闻
            http://example.com/bbc.m3u8
            #EXTINF:-1 tvg-name="凤凰中文" group-title="港澳",凤凰中文
            http://example.com/phoenix.m3u8
        """.trimIndent()
        File(tempFolder.root, "iptv.txt").writeText(m3u)

        val result = IptvRepository().getIptvGroupList(
            "http://127.0.0.1:9/live.m3u",
            cacheTime = 60_000,
            simplify = true,
        )

        // 只剩央视与卫视两个分组；国际、港澳两个分组过滤后没台、
        // 整组去掉，不留空分组
        assertEquals(2, result.size)
        assertEquals("央视", result[0].name)
        assertEquals(listOf("CCTV-1"), result[0].iptvList.map { it.name })
        assertEquals("卫视", result[1].name)
        assertEquals(listOf("湖南卫视"), result[1].iptvList.map { it.name })
    }

    @Test
    fun `simplify keeps cctv regardless of case`() {
        assertTrue(simplifyTest(Iptv(name = "CCTV-1")))
        assertTrue(simplifyTest(Iptv(name = "cctv-1")))
        assertTrue(simplifyTest(Iptv(name = "CCTV-5+")))
        assertTrue(simplifyTest(Iptv(name = "CCTV")))
    }

    @Test
    fun `simplify keeps channels ending with satellite suffix`() {
        assertTrue(simplifyTest(Iptv(name = "湖南卫视")))
        assertTrue(simplifyTest(Iptv(name = "北京卫视")))
        assertTrue(simplifyTest(Iptv(name = "凤凰卫视")))
        assertTrue(simplifyTest(Iptv(name = "卫视")))
    }

    @Test
    fun `simplify drops others`() {
        assertFalse(simplifyTest(Iptv(name = "凤凰中文")))
        assertFalse(simplifyTest(Iptv(name = "BBC 新闻")))
        assertFalse(simplifyTest(Iptv(name = "")))
        // cctv 不在开头、卫视不在结尾时都不要：这档区分“开头/结尾”
        // 与“包含”，改成 contains 会让这两档翻过来
        assertFalse(simplifyTest(Iptv(name = "高清CCTV-1")))
        assertFalse(simplifyTest(Iptv(name = "湖南卫视高清")))
    }
    @Test
    fun `empty group list throws`() {
        assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = false)
        }
    }

    @Test
    fun `empty group list after simplify throws with simplify hint`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = true)
        }
        assertTrue(ex.message?.contains("精简") == true)
    }

    @Test
    fun `empty group list throws EmptyIptvSourceException`() {
        // 空源报错的文案直接显示在错误页上（MainViewModel 把 ex.message
        // 放进 LeanbackMainUiState.Error），只断言类型不够：文案被改掉也没人发现
        val ex = assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = false)
        }
        assertTrue(ex.message?.contains("直播源里没有频道") == true)
    }

    @Test
    fun `empty group list after simplify throws EmptyIptvSourceException`() {
        val ex = assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = true)
        }
        assertTrue(ex.message?.contains("精简后没有频道") == true)
    }

    @Test
    fun `groups with no channels throw`() {
        assertThrows(IllegalStateException::class.java) {
            checkIptvGroupListNotEmpty(
                IptvGroupList(listOf(IptvGroup("央视", IptvList(emptyList())))),
                simplified = false,
            )
        }
    }

    @Test
    fun `non-empty group list passes`() {
        // 不抛错即通过
        checkIptvGroupListNotEmpty(
            IptvGroupList(
                listOf(
                    IptvGroup(
                        "央视",
                        IptvList(listOf(Iptv(name = "CCTV-1"))),
                    )
                )
            ),
            simplified = false,
        )
    }
}
