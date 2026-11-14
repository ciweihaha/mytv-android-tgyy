package top.yogiczy.mytv.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import top.yogiczy.mytv.data.utils.HttpStatusException
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
            val call = client.newCall(request)
            // 取消要能打断阻塞的 execute()/copyTo：withContext 的取消是协作
            // 式的，阻塞调用本身不会停——没这行时取消下载要等整包下完才抛，
            // 约 25MB 的更新包在慢网下能拖很久。另起一个协程守着：父协程一
            // 取消它就先收尾、顺手取消这次 HTTP 调用，读流立刻抛错；正常
            // 下完时外层 finally 把它取消掉，对已完成的调用再取消也没影响
            // （Job.invokeOnCancellation 不是公开 API，只能用这个公开写法）
            val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }

            try {
                // 响应用完就关：原先只在成功读流时关了 body，HTTP 出错
                // （404/500）直接抛错、响应体没关，连接回不到连接池；
                // 更新服务器出问题、反复下载失败会占满单 host 的连接槽，
                // 后续下载一直等连接。use 保证成功、出错两条路都关，和
                // 测速那边 .execute().use 的写法一致
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw HttpStatusException(
                            response.code,
                            "下载文件失败：服务器返回 ${response.code}，请检查下载链接"
                        )
                    }

                    val file = File(filePath)
                    // 流式写入：原先 body.bytes() 会把整个文件读进内存，
                    // 在低内存电视盒子上下载 APK（~25 MB）时容易 OOM
                    FileOutputStream(file).use { fos ->
                        response.body?.byteStream()?.use { it.copyTo(fos) }
                            ?: throw Exception("下载文件失败: 响应体为空")
                    }
                }
            } catch (ex: Exception) {
                log.e("下载文件失败", ex)
                // 取消不是失败：call.cancel() 抛的是 IOException，不是取消
                // 异常——协程已取消说明这次失败就是取消，原样抛取消异常，
                // 别包成网络错（1663 轮已让调用方按取消处理：删残文件、不
                // 弹“下载更新失败”；包错了会把取消又绕回失败那条路）
                if (ex is CancellationException) throw ex
                currentCoroutineContext().ensureActive()
                // 服务器回了明确状态码就原样抛：不是网络问题，别包成
                // “请检查网络连接”误导用户去查网络
                if (ex is HttpStatusException) throw ex
                throw Exception("下载文件失败，请检查网络连接", ex)
            } finally {
                cancelCall.cancel()
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
                    val progress = progressPercent(totalBytesRead, contentLength())
                    // 只有百分比变化时才回调：原先每读一块（约 8KB）就新起
                    // 一个协程回调，25MB 的包约 3200 次，且大多是重复百分比；
                    // 回调方 showToast 内部自己会切到 composition 作用域，
                    // 这里直接调即可
                    if (progress != null && progress != lastProgress) {
                        lastProgress = progress
                        onProgressCb?.invoke(progress)
                    }
                    return bytesRead
                }
            }.buffer()
        }
    }
}