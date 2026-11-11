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
import top.yogiczy.mytv.utils.progressPercent
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * 局域网访问码：6 位数字，进程每次启动重新生成，显示在设置页"更多"里。
 * 网页端修改设置与上传安装包时，必须在请求头 X-Auth-Token 里带上它，
 * 否则返回 401。只读接口（查看设置、日志）不设限。
 * 顶层函数与 HttpServer 对象无关，方便单元测试直接调用。
 */
internal fun generateAccessCode(): String =
    (1..6).joinToString("") { (0..9).random().toString() }

internal fun isAuthorizedAccessCode(provided: String?, expected: String): Boolean =
    !provided.isNullOrBlank() && provided == expected

/**
 * 访问码防暴力破解：窗口内失败次数达到上限即节流。调用方在判断前先清
 * 掉窗口外的旧记录；只记失败尝试，成功不影响计数。
 */
internal fun isAuthThrottled(
    failedAttempts: List<Long>,
    now: Long,
    limit: Int = 5,
    windowMs: Long = 60_000L,
): Boolean = failedAttempts.count { now - it < windowMs } >= limit

/**
 * 网页端设置值：空串/空白串保留当前值。optString 已处理缺失字段与 JSON
 * null（给当前值），但空串会原样存进 SP：下次读出来虽然靠 SP 的
 * ifBlank 回落到内置默认地址，用户的自定义地址却已被悄悄清空，还触发
 * 了缓存清理与重载。
 * 顶层函数与 HttpServer 对象无关，方便单元测试直接调用。
 */
internal fun keepCurrentIfBlank(newValue: String, currentValue: String): String =
    newValue.ifBlank { currentValue }

/**
 * 局域网地址候选：address 是某网卡上的地址，isPointToPoint 表示所属
 * 网卡是否为点对点接口（VPN 的 tun 接口通常是点对点）。
 */
internal data class LanAddressCandidate(
    val address: InetAddress,
    val isPointToPoint: Boolean,
)

/**
 * 从本机地址里选一个局域网地址，优先级：
 * 1. 非点对点网卡的内网地址（10/8、172.16/12、192.168/16）
 * 2. 非点对点网卡的其它非回环 IPv4
 * 3. 点对点网卡的内网地址（只有 VPN 时的回落）
 * 4. 点对点网卡的其它非回环 IPv4
 * 都没有返回 null。
 * VPN 常用 10.x 段（也是内网段），只按地址挑会选中 VPN 地址，
 * 设置页与失败屏的二维码就指到了局域网内访问不到的地址；点对点
 * 接口（tun/ppp）基本都是 VPN/拨号，不是局域网网卡，先排除。
 */
internal fun pickLanAddress(candidates: List<LanAddressCandidate>): InetAddress? {
    val usable = candidates.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
    return usable.firstOrNull { !it.isPointToPoint && it.address.isSiteLocalAddress }?.address
        ?: usable.firstOrNull { !it.isPointToPoint }?.address
        ?: usable.firstOrNull { it.address.isSiteLocalAddress }?.address
        ?: usable.firstOrNull()?.address
}

object HttpServer : Loggable() {
    private const val SERVER_PORT = 10481

    /**
     * 本次启动的网页端访问码（6 位数字）：设置页"更多"里显示；网页端
     * 修改设置与上传安装包时在请求头 X-Auth-Token 里带上它。应用重启后更换。
     */
    val accessCode: String by lazy { generateAccessCode() }

    private val uploadedApkFile = File(AppGlobal.cacheDir, "uploaded_apk.apk").apply {
        deleteOnExit()
    }

    private var showToast: (String) -> Unit = { }

    private var onSettingsChanged: (() -> Unit)? = null

    private var onSettingsValuesChanged: (() -> Unit)? = null

    private var isStarted = false

    private val authLock = Any()
    private val failedAuthAttempts = ArrayDeque<Long>()

    /**
     * 写接口的鉴权门：60 秒内失败 5 次后直接返回 429，直到窗口滑过去；
     * 访问码不对返回 401 并记一次失败。返回 false 时调用方直接 return。
     */
    private fun checkAuth(
        request: AsyncHttpServerRequest,
        response: AsyncHttpServerResponse,
    ): Boolean {
        val now = System.currentTimeMillis()
        synchronized(authLock) {
            failedAuthAttempts.removeAll { now - it >= 60_000L }
            if (isAuthThrottled(failedAuthAttempts, now)) {
                wrapResponse(response).code(429).send("too many attempts")
                return false
            }
        }
        if (!isAuthorizedAccessCode(request.headers["X-Auth-Token"], accessCode)) {
            synchronized(authLock) { failedAuthAttempts.addLast(now) }
            wrapResponse(response).code(401).send("unauthorized")
            return false
        }
        return true
    }

    // 每次现算不缓存：IP 会变（WiFi 重连、切网络、开热点），lazy 只算
    // 一次会让设置页和加载失败页的二维码一直显示旧地址，扫了连不上；
    // 读网卡只是本地枚举、无网络 IO，现算成本可忽略
    val serverUrl: String
        get() = "http://${getLocalIpAddress()}:${SERVER_PORT}"

    /**
     * @param onSettingsChanged 网页端改了直播源/节目单地址时调用，由 LeanbackActivity
     * 传入 LeanbackMainViewModel.refresh()，使新源立即生效、无需重启应用
     * @param onSettingsValuesChanged 网页端改了任一设置值（源地址或播放器 UA）
     * 时调用，由 LeanbackActivity 传入 LeanbackSettingsViewModel.syncFromSP()，
     * 使设置界面不再显示旧值；只改 UA 时不触发 onSettingsChanged（不用重载）
     */
    fun start(
        context: Context,
        showToast: (String) -> Unit,
        onSettingsChanged: (() -> Unit)? = null,
        onSettingsValuesChanged: (() -> Unit)? = null,
    ) {
        HttpServer.onSettingsChanged = onSettingsChanged
        HttpServer.onSettingsValuesChanged = onSettingsValuesChanged
        HttpServer.showToast = showToast
        // 服务与进程同寿：重复 start（Activity 重建等）只更新回调，不再
        // 重复监听端口，否则第二次 listen 抛 BindException，还会误弹
        // "启动失败"（其实第一个服务还在正常跑）
        if (isStarted) return
        isStarted = true
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

                log.i("服务已启动: 0.0.0.0:${SERVER_PORT}")
            } catch (ex: Exception) {
                isStarted = false
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
            // 读完就关流：原先 openRawResource 拿到的流读完没关，每次打开
            // 网页（首页、样式、脚本各一次）都漏一个文件描述符，要等回收
            // 才释放；use 保证读完（包括读一半抛错）就关
            send(context.resources.openRawResource(id).use { it.readBytes().decodeToString() })
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
        // 写接口必须带访问码：局域网内任何设备否则都能悄悄改掉直播源
        if (!checkAuth(request, response)) return

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

        val iptvSourceUrl = keepCurrentIfBlank(
            jsonObject.optString("iptvSourceUrl", SP.iptvSourceUrl), SP.iptvSourceUrl
        )
        val epgXmlUrl = keepCurrentIfBlank(
            jsonObject.optString("epgXmlUrl", SP.epgXmlUrl), SP.epgXmlUrl
        )
        val videoPlayerUserAgent = keepCurrentIfBlank(
            jsonObject.optString("videoPlayerUserAgent", SP.videoPlayerUserAgent),
            SP.videoPlayerUserAgent,
        )

        var sourceChanged = false
        var valuesChanged = false

        if (SP.iptvSourceUrl != iptvSourceUrl) {
            SP.iptvSourceUrl = iptvSourceUrl
            IptvRepository().clearCache()
            sourceChanged = true
            valuesChanged = true
        }

        if (SP.epgXmlUrl != epgXmlUrl) {
            SP.epgXmlUrl = epgXmlUrl
            EpgRepository().clearCache()
            sourceChanged = true
            valuesChanged = true
        }

        if (SP.videoPlayerUserAgent != videoPlayerUserAgent) {
            SP.videoPlayerUserAgent = videoPlayerUserAgent
            valuesChanged = true
        }

        // 先同步设置界面的显示值，再重载新源；只改 UA 时只同步、不重载
        if (valuesChanged) {
            onSettingsValuesChanged?.invoke()
        }

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
        // 上传接口同样必须带访问码；安装本身仍要电视上手动确认
        if (!checkAuth(request, response)) return

        val body = request.getBody<MultipartFormDataBody>()
        // 非 multipart 请求没有 body：原先直接空指针
        if (body == null) {
            wrapResponse(response).code(400).send("invalid multipart body")
            return
        }

        // 收到文件分片时才创建输出流：原先请求一到就 open，即便没有文件分片
        // 也会把已有的 uploaded_apk.apk 截断成空文件
        var os: java.io.OutputStream? = null
        // 本次上传的临时文件：写完再原子改名成正式文件（见 end 回调）
        var tempApkFile: File? = null
        // multipart 的 Content-Length 是整个请求体（含分隔符），只能做近似
        // 进度；取不到或非法时不报百分比
        val contentLength = request.headers["Content-Length"]?.toLongOrNull() ?: -1L
        var hasReceived = 0L
        var lastProgress = -1

        body.setMultipartCallback { part ->
            if (part.isFile) {
                if (os == null) {
                    // 每次上传写独立临时文件：两个浏览器标签同时上传不再
                    // 往同一个文件里交叉写；中途断开也只留一个临时残文件
                    // （清缓存时清理），不会截断上一次的完整包；临时文
                    // 件建不出来时回退到原先的直接写
                    tempApkFile = try {
                        File.createTempFile(
                            "uploaded_apk_", ".apk", AppGlobal.cacheDir
                        )
                    } catch (_: Exception) {
                        null
                    }
                    os = (tempApkFile ?: uploadedApkFile).outputStream()
                }
                body.setDataCallback { _, bb ->
                    val byteArray = bb.allByteArray
                    hasReceived += byteArray.size
                    // 分片回调很频繁：只有百分比变化时才弹提示，避免几千次 Toast 刷新
                    val progress = progressPercent(hasReceived, contentLength)
                    if (progress != null && progress != lastProgress) {
                        lastProgress = progress
                        showToast("正在接收文件: $progress%")
                    }
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
            // 文件收完才回响应：原先一收到请求就回 "success"，网页端会
            // 提前关掉加载提示，而文件其实还在传；没收到文件内容时回 400
            if (hasReceived > 0) {
                // 收完再原子改名成正式文件（同目录 rename 是原子操作）；
                // 改名失败（极少）就直接装临时文件；没用临时文件时是原
                // 先的直接写，装正式文件
                val apkFile = tempApkFile?.let { temp ->
                    if (temp.renameTo(uploadedApkFile)) uploadedApkFile else temp
                } ?: uploadedApkFile
                ApkInstaller.installApk(context, apkFile.path)
                wrapResponse(response).send("success")
            } else {
                tempApkFile?.delete()
                wrapResponse(response).code(400).send("no file received")
            }
        }
    }

    private fun getLocalIpAddress(): String {
        val defaultIp = "0.0.0.0"

        return try {
            val candidates = NetworkInterface.getNetworkInterfaces()
                ?.asSequence().orEmpty()
                .flatMap { iface ->
                    iface.inetAddresses.asSequence()
                        .map { LanAddressCandidate(it, iface.isPointToPoint) }
                }
                .toList()
            pickLanAddress(candidates)?.hostAddress ?: defaultIp
        } catch (ex: SocketException) {
            log.e("IP Address: ${ex.message}", ex)
            defaultIp
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