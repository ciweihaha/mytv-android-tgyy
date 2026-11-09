package top.yogiczy.mytv.data.utils

import okhttp3.OkHttpClient
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
