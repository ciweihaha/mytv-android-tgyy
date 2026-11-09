package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
                  "name": "mytv-1.4.4.apk",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
                  "size": 25800000
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
        assertEquals(25800000L, result.downloadSize)
    }

    @Test
    fun `tag without v prefix is kept as-is`() = runBlocking {
        val data = """
            {
              "tag_name": "1.4.5",
              "body": "",
              "assets": [
                {
                  "name": "mytv-1.4.5.apk",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/1.4.5/mytv-1.4.5.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("1.4.5", result.version)
    }

    @Test
    fun `picks the apk asset when it is not first`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "更新说明",
              "assets": [
                {
                  "name": "mytv-1.4.4.apk.sha256",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk.sha256",
                  "size": 128
                },
                {
                  "name": "mytv-1.4.4.apk",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
                  "size": 25800000
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(
            "https://mirror.ghproxy.com/https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
            result.downloadUrl,
        )
        assertEquals(25800000L, result.downloadSize)
    }

    @Test
    fun `empty assets fails instead of downloading the wrong file`() {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "更新说明",
              "assets": []
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { parser.parse(data) }
        }
    }

    @Test
    fun `null body becomes empty description instead of literal null`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": null,
              "assets": [
                {
                  "name": "mytv-1.4.4.apk",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
                  "size": 25800000
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("", result.description)
    }

    @Test
    fun `release without any apk fails`() {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "更新说明",
              "assets": [
                {
                  "name": "mytv-1.4.4.apk.sha256",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk.sha256",
                  "size": 128
                }
              ]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { parser.parse(data) }
        }
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
                  "name": "mytv-1.4.4.apk",
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
        // Gitee 的发行版信息不带 size 时记 -1（下载时不校验）
        assertEquals(-1L, result.downloadSize)
    }

    @Test
    fun `picks the apk asset when it is not first`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": "更新说明",
              "assets": [
                {
                  "name": "mytv-1.4.4.apk.sha256",
                  "browser_download_url": "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk.sha256",
                  "size": 128
                },
                {
                  "name": "mytv-1.4.4.apk",
                  "browser_download_url": "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
                  "size": 25800000
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals(
            "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk",
            result.downloadUrl,
        )
        assertEquals(25800000L, result.downloadSize)
    }

    @Test
    fun `null body becomes empty description instead of literal null`() = runBlocking {
        val data = """
            {
              "tag_name": "v1.4.4",
              "body": null,
              "assets": [
                {
                  "name": "mytv-1.4.4.apk",
                  "browser_download_url": "https://gitee.com/yaoxieyoulei/mytv-android/releases/download/v1.4.4/mytv-1.4.4.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("", result.description)
    }
}
