package top.yogiczy.mytv.data.repositories.iptv

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.entities.Iptv
import top.yogiczy.mytv.data.entities.IptvGroup
import top.yogiczy.mytv.data.entities.IptvGroupList
import top.yogiczy.mytv.data.entities.IptvList

class IptvRepositoryTest {
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
        assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = false)
        }
    }

    @Test
    fun `empty group list after simplify throws EmptyIptvSourceException`() {
        assertThrows(EmptyIptvSourceException::class.java) {
            checkIptvGroupListNotEmpty(IptvGroupList(), simplified = true)
        }
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
