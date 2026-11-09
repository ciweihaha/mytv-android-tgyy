package top.yogiczy.mytv.data.repositories.epg.fetcher

import okhttp3.Response
import java.io.BufferedInputStream
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
         *
         * 流式嗅探 + 解压：原先 body.bytes() 把整个响应体（节目单常
         * 有几十 MB）先整份读进内存，gzip 还要再整份解压出一份，两份
         * 大数组 + 最后的 String 同时在内存里，低内存盒子容易 OOM。
         * 现只读前 2 字节判 gzip 魔数就流式解压，压缩过的那份不再整
         * 份落内存（解压出的那份仍要整份成串，这是 fetch 返回 String
         * 的接口决定的，彻底流式要改解析器接口，另议）
         */
        fun decodeBody(response: Response): String {
            val stream = response.body?.byteStream() ?: return ""
            return BufferedInputStream(stream).use { buffered ->
                buffered.mark(2)
                val first = buffered.read()
                val second = buffered.read()
                buffered.reset()
                if (first == 0x1F && second == 0x8B) {
                    GZIPInputStream(buffered).use { it.readBytes().toString(Charsets.UTF_8) }
                } else {
                    buffered.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
    }
}
