package top.yogiczy.mytv.data.repositories.git.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.utils.compareVersion

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
    fun `tv prefixed tag is stripped to plain version`() = runBlocking {
        // 上游最新 release 的 tag 是 "tv2.2.7"：只去小写 v 会留成
        // "tv2.2.7"，三处显示又各拼一个 v，最后显示成 "vtv2.2.7"
        val data = """
            {
              "tag_name": "tv2.2.7",
              "body": "更新说明",
              "assets": [
                {
                  "name": "mytv-2.2.7.apk",
                  "browser_download_url": "https://github.com/yaoxieyoulei/mytv-android/releases/download/tv2.2.7/mytv-2.2.7.apk"
                }
              ]
            }
        """.trimIndent()

        val result = parser.parse(data)

        assertEquals("2.2.7", result.version)
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

class GitReleaseParserSelectTest {
    @Test
    fun `selects github parser for github urls`() {
        assertTrue(
            GitReleaseParser.selectParser(
                "https://api.github.com/repos/yaoxieyoulei/mytv-android/releases/latest"
            ) is GithubGitReleaseParser
        )
    }

    @Test
    fun `selects github parser for the fork update url`() {
        // 更新检查地址必须指本 fork：上游最新 release 是 2024 年的
        // tv2.2.7，比已装的 2.2.8 还旧，查上游永远报无更新
        assertTrue(Constants.GIT_RELEASE_LATEST_URL.contains("ciweihaha/mytv-android-tgyy"))
        assertTrue(
            GitReleaseParser.selectParser(Constants.GIT_RELEASE_LATEST_URL)
                is GithubGitReleaseParser
        )
    }

    @Test
    fun `parses a fork release with v-prefixed tag and fork asset name`() = runBlocking {
        val data = """
            {
              "tag_name": "v2.2.9",
              "body": "更新说明",
              "assets": [
                {
                  "name": "mytv-android-2.2.9.apk",
                  "browser_download_url": "https://github.com/ciweihaha/mytv-android-tgyy/releases/download/v2.2.9/mytv-android-2.2.9.apk",
                  "size": 6440504
                }
              ]
            }
        """.trimIndent()

        val result = GithubGitReleaseParser().parse(data)

        assertEquals("2.2.9", result.version)
        assertEquals(
            "https://mirror.ghproxy.com/https://github.com/ciweihaha/mytv-android-tgyy/releases/download/v2.2.9/mytv-android-2.2.9.apk",
            result.downloadUrl,
        )
        assertEquals(6440504L, result.downloadSize)
        assertTrue(result.version.compareVersion("2.2.8") > 0)
    }

    @Test
    fun `selects gitee parser for gitee urls`() {
        assertTrue(
            GitReleaseParser.selectParser(
                "https://gitee.com/api/v5/repos/yaoxieyoulei/mytv-android/releases/latest"
            ) is GiteeGitReleaseParser
        )
    }

    @Test
    fun `returns null for unsupported update source`() {
        assertNull(
            GitReleaseParser.selectParser("https://example.com/releases/latest")
        )
    }
}
