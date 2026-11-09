package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class XmlGzEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".gz")
    }

    override fun fetch(response: Response): String {
        // 空响应体（如 204）时返回空串，由上层按空节目单处理，而不是 NPE
        val gzData = response.body?.bytes() ?: return ""
        val stringBuilder = StringBuilder()
        GZIPInputStream(ByteArrayInputStream(gzData)).use { gzipInputStream ->
            BufferedReader(InputStreamReader(gzipInputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line).append("\n")
                }
            }
        }
        return stringBuilder.toString()
    }
}