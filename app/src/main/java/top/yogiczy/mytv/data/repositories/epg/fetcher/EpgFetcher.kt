package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 节目单获取接口
 */
interface EpgFetcher {
    /**
     * 是否支持该格式
     */
    fun isSupport(url: String): Boolean

    /**
     * 获取节目单
     */
    fun fetch(response: Response): String

    companion object {
        val instances = listOf(
            XmlEpgFetcher(),
            XmlGzEpgFetcher(),
            DefaultEpgFetcher(),
        )

        /**
         * 去掉 query 与 fragment 后再比后缀：`epg.xml?token=xxx`、
         * `epg.xml.gz#frag` 这类真实订阅地址也应命中对应解析器
         */
        fun matchExtension(url: String, extension: String): Boolean {
            return url.substringBefore('?').substringBefore('#')
                .endsWith(extension, ignoreCase = true)
        }

        /**
         * 按内容解码响应体：gzip 魔数（0x1F 0x8B）开头则解压，否则按
         * UTF-8 当纯文本。无扩展名 / 带参数的地址走 Default 解析器时，
         * 靠它而不是返回空节目单；同时兜底了服务器把 gzip 文件挂在
         * .xml 地址下的情况
         */
        fun decodeBody(response: Response): String {
            val data = response.body?.bytes() ?: return ""
            if (data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
                return GZIPInputStream(ByteArrayInputStream(data)).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }
            return data.toString(Charsets.UTF_8)
        }
    }
}
