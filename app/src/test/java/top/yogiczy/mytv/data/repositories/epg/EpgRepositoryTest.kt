package top.yogiczy.mytv.data.repositories.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.AppGlobal
import java.io.File

class EpgRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        AppGlobal.cacheDir = tempFolder.root
    }

    @Test
    fun `clearCache also clears the raw xml cache`() {
        // 节目单是两级缓存（epg.xml 原文 + epg.json 解析结果）：只清
        // 解析结果时，换源后新源拉取失败会回落到旧源的 epg.xml，把旧
        // 源的节目单当成新源的显示
        File(tempFolder.root, "epg.json").writeText("{}")
        File(tempFolder.root, "epg.xml").writeText("<tv></tv>")

        EpgRepository().clearCache()

        assertFalse(File(tempFolder.root, "epg.json").exists())
        assertFalse(File(tempFolder.root, "epg.xml").exists())
    }

    @Test
    fun `clearCache leaves other files alone`() {
        File(tempFolder.root, "epg.json").writeText("{}")
        File(tempFolder.root, "iptv.txt").writeText("channels")

        EpgRepository().clearCache()

        assertFalse(File(tempFolder.root, "epg.json").exists())
        assertTrue(File(tempFolder.root, "iptv.txt").exists())
    }
}
