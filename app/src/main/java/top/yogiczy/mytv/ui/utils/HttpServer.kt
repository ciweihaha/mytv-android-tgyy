package top.yogiczy.mytv.ui.utils

import android.content.Context
import android.widget.Toast
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.body.MultipartFormDataBody
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.AsyncHttpServerRequest
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.yogiczy.mytv.AppGlobal
import top.yogiczy.mytv.R
import top.yogiczy.mytv.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.data.utils.Constants
import top.yogiczy.mytv.utils.ApkInstaller
import top.yogiczy.mytv.utils.Loggable
import top.yogiczy.mytv.utils.Logger
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

object HttpServer : Loggable() {
    private const val SERVER_PORT = 10481

    private val uploadedApkFile = File(AppGlobal.cacheDir, "uploaded_apk.apk").apply {
        deleteOnExit()
    }

    private var showToast: (String) -> Unit = { }

    private var onSettingsChanged: (() -> Unit)? = null

    val serverUrl: String by lazy {
        "http://${getLocalIpAddress()}:${SERVER_PORT}"
    }

    /**
     * @param onSettingsChanged 网页端改了直播源/节目单地址时调用，由 LeanbackActivity
     * 传入 LeanbackMainViewModel.refresh()，使新源立即生效、无需重启应用
     */
    fun start(
        context: Context,
        showToast: (String) -> Unit,
        onSettingsChanged: (() -> Unit)? = null,
    ) {
        HttpServer.onSettingsChanged = onSettingsChanged
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val server = AsyncHttpServer()
                server.listen(AsyncServer.getDefault(), SERVER_PORT)

                server.get("/") { _, response ->
                    handleRawResource(response, context, "text/html", R.raw.index)
                }
                server.get("/index_css.css") { _, response ->
                    handleRawResource(response, context, "text/css", R.raw.index_css)
                }
                server.get("/index_js.js") { _, response ->
                    handleRawResource(response, context, "text/javascript", R.raw.index_js)
                }

                server.get("/api/settings") { _, response ->
                    handleGetSettings(response)
                }

                server.post("/api/settings") { request, response ->
                    handleSetSettings(request, response)
                }

                server.post("/api/upload/apk") { request, response ->
                    handleUploadApk(request, response, context)
                }

                HttpServer.showToast = showToast
                log.i("服务已启动: 0.0.0.0:${SERVER_PORT}")
            } catch (ex: Exception) {
                log.e("服务启动失败: ${ex.message}", ex)
                launch(Dispatchers.Main) {
                    Toast.makeText(context, "设置服务启动失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun wrapResponse(response: AsyncHttpServerResponse) = response.apply {
        headers.set(
            "Access-Control-Allow-Methods", "POST, GET, DELETE, PUT, OPTIONS"
        )
        headers.set("Access-Control-Allow-Origin", "*")
        headers.set(
            "Access-Control-Allow-Headers", "Origin, Content-Type, X-Auth-Token"
        )
    }

    private fun handleRawResource(
        response: AsyncHttpServerResponse,
        context: Context,
        contentType: String,
        id: Int,
    ) {
        wrapResponse(response).apply {
            setContentType(contentType)
            send(context.resources.openRawResource(id).readBytes().decodeToString())
        }
    }

    private fun handleGetSettings(response: AsyncHttpServerResponse) {
        wrapResponse(response).apply {
            setContentType("application/json")
            send(
                Json.encodeToString(
                    AllSettings(
                        appTitle = Constants.APP_TITLE,
                        appRepo = Constants.APP_REPO,
                        iptvSourceUrl = SP.iptvSourceUrl,
                        epgXmlUrl = SP.epgXmlUrl,
                        videoPlayerUserAgent = SP.videoPlayerUserAgent,
                        logHistory = Logger.history,
                    )
                )
            )
        }
    }

    private fun handleSetSettings(
        request: AsyncHttpServerRequest,
        response: AsyncHttpServerResponse,
    ) {
        val body = request.getBody<JSONObjectBody>()
        if (body == null) {
            wrapResponse(response).code(400).send("invalid json body")
            return
        }

        // 缺失字段或 JSON null 时保留当前值：原先 body.get(...).toString() 在
        // 字段缺失时抛 JSONException、在值为 null 时把字面量 "null" 存为 URL
        val jsonObject = try {
            body.get()
        } catch (ex: Exception) {
            log.e("解析设置失败", ex)
            wrapResponse(response).code(400).send("invalid json body")
            return
        }

        val iptvSourceUrl = jsonObject.optString("iptvSourceUrl", SP.iptvSourceUrl)
        val epgXmlUrl = jsonObject.optString("epgXmlUrl", SP.epgXmlUrl)
        val videoPlayerUserAgent = jsonObject.optString("videoPlayerUserAgent", SP.videoPlayerUserAgent)

        var sourceChanged = false

        if (SP.iptvSourceUrl != iptvSourceUrl) {
            SP.iptvSourceUrl = iptvSourceUrl
            IptvRepository().clearCache()
            sourceChanged = true
        }

        if (SP.epgXmlUrl != epgXmlUrl) {
            SP.epgXmlUrl = epgXmlUrl
            EpgRepository().clearCache()
            sourceChanged = true
        }

        SP.videoPlayerUserAgent = videoPlayerUserAgent

        // 只改播放器 UA 不用重载；源变了才通知主界面重新加载新源
        if (sourceChanged) {
            onSettingsChanged?.invoke()
            showToast("设置已更新，正在重新加载")
        }

        wrapResponse(response).send("success")
    }

    private fun handleUploadApk(
        request: AsyncHttpServerRequest,
        response: AsyncHttpServerResponse,
        context: Context,
    ) {
        val body = request.getBody<MultipartFormDataBody>()

        // 收到文件分片时才创建输出流：原先请求一到就 open，即便没有文件分片
        // 也会把已有的 uploaded_apk.apk 截断成空文件
        var os: java.io.OutputStream? = null
        val contentLength = request.headers["Content-Length"]?.toLong() ?: 1
        var hasReceived = 0L

        body.setMultipartCallback { part ->
            if (part.isFile) {
                if (os == null) {
                    os = uploadedApkFile.outputStream()
                }
                body.setDataCallback { _, bb ->
                    val byteArray = bb.allByteArray
                    hasReceived += byteArray.size
                    showToast("正在接收文件: ${(hasReceived * 100f / contentLength).toInt()}%")
                    os?.write(byteArray)
                }
            }
        }

        body.setEndCallback {
            showToast("文件接收完成")
            body.dataEmitter.close()
            try {
                os?.flush()
            } finally {
                try {
                    os?.close()
                } catch (_: Exception) {
                    // 关闭失败不影响后续流程
                }
                os = null
            }
            // 只在确实收到文件内容时才触发安装
            if (hasReceived > 0) {
                ApkInstaller.installApk(context, uploadedApkFile.path)
            }
        }

        wrapResponse(response).send("success")
    }

    private fun getLocalIpAddress(): String {
        val defaultIp = "0.0.0.0"

        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: defaultIp
                    }
                }
            }
            return defaultIp
        } catch (ex: SocketException) {
            log.e("IP Address: ${ex.message}", ex)
            return defaultIp
        }
    }
}

@Serializable
private data class AllSettings(
    val appTitle: String,
    val appRepo: String,
    val iptvSourceUrl: String,
    val epgXmlUrl: String,
    val videoPlayerUserAgent: String,

    val logHistory: List<Logger.HistoryItem>,
)