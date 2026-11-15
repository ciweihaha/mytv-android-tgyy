package top.yogiczy.mytv.data.repositories.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

class IptvRepositoryTest {
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
