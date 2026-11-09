package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubGitReleaseParserTest {
    private val parser = GithubGitReleaseParser()

    @Test
    fun `isSupport accepts github urls only`() {
        assertTrue(parser.isSupport("https://api.github.com/repos/yaoxieyoulei/mytv-android/releases/latest"))
        assertFalse(parser.isSupport("https://gitee.com/api/v5/repos/yaoxieyoulei/mytv-android/releases/latest"))
    }

    @Test
    fun `parses tag version asset url and body`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "## 更新内容\n- 修复已知问题",
              "assets": [
                {
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("1.4.4", result.version)
        assertEquals(
            "https://mirror.ghproxy.com/https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
            result.downloadUrl,
        )
        assertEquals("## 更新内容\n- 修复已知问题", result.description)
    }

    @Test
    fun `tag without v prefix is kept as-is`() = runBlocking {
        val data = """
            {
              "tag_name": "1.4.5",
              "body": "",
              "assets": [
                {
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/1.4.5/mytv-1.4.5.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("1.4.5", result.version)
    }
}

class GiteeGitReleaseParserTest {
    private val parser = GiteeGitReleaseParser()

    @Test
    fun `isSupport accepts gitee urls only`() {
        assertTrue(parser.isSupport("https://gitee.com/api/v5/repos/yaoxieyoulei/mytv-android/releases/latest"))
        assertFalse(parser.isSupport("https://api.github.com/repos/yaoxieyoulei/mytv-android/releases/latest"))
    }

    @Test
    fun `parses tag version asset url and body without proxy`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "更新说明",
              "assets": [
                {
                  "browser_download_url": "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("1.4.4", result.version)
        assertEquals(
            "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
            result.downloadUrl,
        )
        assertEquals("更新说明", result.description)
    }
}
