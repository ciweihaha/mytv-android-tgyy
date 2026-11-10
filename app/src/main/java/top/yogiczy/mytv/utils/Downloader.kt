package top.yogiczy.mytv.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import top.yogiczy.mytv.data.utils.sharedHttpClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object Downloader : Loggable() {
    suspend fun downloadTo(url: String, filePath: String, onProgressCb: ((Int) -> Unit)?) =
        withContext(Dispatchers.IO) {
            log.d("下载文件: $url")
            val interceptor = Interceptor { chain ->
                val originalResponse = chain.proceed(chain.request())
                originalResponse.newBuilder()
                    .body(DownloadResponseBody(originalResponse, onProgressCb)).build()
            }

            // 从共用客户端派生：复用它的连接池与线程池，不再每次下载都
            // 新建一个客户端；同时继承连接/读/写单次超时——原先的全新客
            // 户端没有任何超时，服务器断流时会永远挂住，更新界面一直停
            // 在“正在下载更新”，重试也被 _isUpdating 挡住。总时长不限：
            // APK 约 25MB，慢网下超过共用客户端的 120 秒总超时也应继续下
            val client = sharedHttpClient.newBuilder()
                .addNetworkInterceptor(interceptor)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()

            try {
                with(client.newCall(request).execute()) {
                    if (!isSuccessful) {
                        throw Exception("下载文件失败: $code")
                    }

                    val file = File(filePath)
                    // 流式写入：原先 body.bytes() 会把整个文件读进内存，
                    // 在低内存电视盒子上下载 APK（~25 MB）时容易 OOM
                    FileOutputStream(file).use { fos ->
                        body?.byteStream()?.use { it.copyTo(fos) }
                            ?: throw Exception("下载文件失败: 响应体为空")
                    }
                }
            } catch (ex: Exception) {
                log.e("下载文件失败", ex)
                throw Exception("下载文件失败，请检查网络连接", ex)
            }
        }

    private class DownloadResponseBody(
        private val originalResponse: okhttp3.Response,
        private val onProgressCb: ((Int) -> Unit)?,
    ) : okhttp3.ResponseBody() {
        override fun contentLength() = originalResponse.body?.contentLength() ?: -1

        override fun contentType() = originalResponse.body?.contentType()

        override fun source(): BufferedSource {
            return object : ForwardingSource(
                originalResponse.body?.source()
                    ?: throw IllegalStateException("下载响应体为空")
            ) {
                var totalBytesRead = 0L
                var lastProgress = -1

                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                    // 长度未知（分块传输）时不报进度，避免算出负数百分比
                    val length = contentLength()
                    if (length > 0) {
                        val progress = (totalBytesRead * 100 / length).toInt()
                        // 只有百分比变化时才回调：原先每读一块（约 8KB）就新起
                        // 一个协程回调，25MB 的包约 3200 次，且大多是重复百分比；
                        // 回调方 showToast 内部自己会切到 composition 作用域，
                        // 这里直接调即可
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgressCb?.invoke(progress)
                        }
                    }
                    return bytesRead
                }
            }.buffer()
        }
    }
}