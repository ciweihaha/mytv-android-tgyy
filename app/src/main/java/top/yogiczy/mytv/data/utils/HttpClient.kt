package top.yogiczy.mytv.data.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 OkHttpClient：原先每次拉源/查更新/测速都 new 一个，
 * 各自带独立的连接池与线程池，既浪费又无法复用连接；默认又没有总超时，
 * 服务器细流式响应时单次读取不断续上、整体可能 hang 很久。
 * OkHttpClient 是线程安全的，设计上就是拿来共用的。
 */
val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}

/**
 * 地址格式错：单独成类、方便调用方认出来——格式错重试多少次都一样，
 * 直播源与节目单拉取的重试点名跳过它，报错立刻显示，不用等 10 次
 * 重试（每次隔 3 秒）跑完；网络错仍是普通 Exception，照常重试
 */
class InvalidSourceUrlException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 服务器回了明确的 HTTP 状态码（404/500 等）：不是网络问题，别包成
 * “请检查网络连接”误导用户去查网络；状态码留在类型里，调用方按
 * 状态码决定文案与重试——4xx（除 408 超时、429 限流）是地址本身
 * 的问题，5xx 与 408/429 是服务器一时的问题。
 */
class HttpStatusException(val code: Int, message: String) : Exception(message)

/**
 * 建 GET 请求：直播源/节目单地址是用户在设置页或网页端手输的，缺协议、
 * 带空格都可能是畸形地址；Request.Builder().url() 在这种地址上抛
 * IllegalArgumentException。原先建请求那行在 try 外、异常原样上抛，
 * 最后错误屏显示的是 OkHttp 的英文原文（"Expected URL scheme ..."），
 * 用户看不懂。只在这里包一层：格式不对就给一句中文、带上原地址，
 * 让用户去检查地址；真正的网络错仍由调用方按原来包成"请检查网络连接"。
 * 顶层函数方便单元测试直接调用。
 */
fun buildGetRequest(url: String, label: String): Request {
    return try {
        Request.Builder().url(url).build()
    } catch (ex: IllegalArgumentException) {
        throw InvalidSourceUrlException("${label}格式不正确，请检查地址：$url", ex)
    }
}
