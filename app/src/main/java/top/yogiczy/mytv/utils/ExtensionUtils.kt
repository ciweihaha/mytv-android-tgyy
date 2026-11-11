package top.yogiczy.mytv.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max

/**
 * 取包信息：API 33 起 getPackageInfo(String, Int) 已废弃，
 * 用 PackageInfoFlags 版；低版本走旧调用。三个调用点（关于页、
 * 应用设置页手动检查、更新页）原来都直接调已废弃的旧版。
 */
fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
}

// 包信息只查一次记下来：原来关于页和更新页各写了一份一模一样的，
// 每次组合都调一次系统接口（跨进程调用），界面一刷新就重新查一遍；
// 版本号在运行中不会变，记下来就够了。两处共用这一份。
@Composable
fun rememberPackageInfo(context: Context = LocalContext.current): PackageInfo =
    remember(context) {
        context.packageManager.getPackageInfoCompat(context.packageName)
    }

fun Long.humanizeMs(): String {
    return when (this) {
        in 0..<60_000 -> "${this / 1000}秒"
        in 60_000..<3_600_000 -> "${this / 60_000}分钟"
        else -> "${this / 3_600_000}小时"
    }
}

// 小时步进：设置页里“节目单刷新时间阈值”和“直播源缓存时间”两项原来各写了一份
// 短按加 1 小时、到顶回 0——一个存小时数（0~11 小时）、一个存毫秒数（0~23 小时），
// 算法一样、单位和上限不同；以后改步进只改这一处。长按回 0 两处仍各写一个 0，不动。
// 毫秒那档能走的值都是整小时（默认 24 小时、步进结果、长按 0），先除成小时再算，
// 结果和原来直接在毫秒上加取余一模一样，包括默认 24 小时按一下变 1 小时。
fun nextHourStep(currentHours: Int, maxHours: Int): Int =
    (currentHours + 1) % maxHours

fun nextHourStepMs(currentMs: Long, maxHours: Int): Long =
    nextHourStep((currentMs / 3_600_000).toInt(), maxHours) * 3_600_000L

// 列表初始滚动位置：全 App 8 处都是同一条算式——当前项下标减 2 再夹到 0，
// 让当前项上方留两行，打开列表就能看到上下文；下标 -1（当前项不在列表里）
// 减完也是负数，同样夹回 0，就是第一行。以后改“留几行”只改这一处。
fun initialScrollIndex(index: Int, above: Int = 2): Int = max(0, index - above)

// 下载/上传进度百分比：下载更新包（Downloader）和局域网上传安装包
// （HttpServer）原来各写了一份——一个是 Long 整数算、一个是 Float 算，
// 两份都是“收到多少占总长百分之几、向下取整、只在变了才报”；以后改
// 口径只改这一处。统一成 Long 整数算：Float 版在大文件下会丢精度，
// 收满时还可能因为浮点舍入算出 99 而不是 100，整数算收满必是 100。
// 总长度未知或非法（<= 0，如分块传输）时返回 null，调用处直接不报进度；
// 收到比总长多（上传的 Content-Length 含分隔符、只是近似值）时和原来
// 一样照实返回超过 100 的数，不额外夹。
fun progressPercent(received: Long, total: Long): Int? {
    if (total <= 0) return null
    return (received * 100 / total).toInt()
}

fun String.isIPv6(): Boolean {
    // host：scheme 之后、第一个 / 之前
    val host = substringAfter("://", this).substringBefore("/")
    if (host.startsWith("[")) {
        // [2001:db8::1] 或 [2001:db8::1]:8080：取括号内判断
        val inner = host.substringAfter("[").substringBefore("]")
        return inner.contains(":") &&
            inner.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }
    // 裸 IPv6（M3U 里常见的不带方括号写法，如 2408:8207::1:8080）：
    // host 里至少两个冒号，且只含十六进制、冒号、点（IPv4 映射形如 ::ffff:192.168.0.1）；
    // IPv4 带端口只有一个冒号，域名含冒号的不是合法 URL，不会误判
    return host.count { it == ':' } >= 2 &&
        host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}

fun String.compareVersion(version2: String): Int {
    fun parseVersion(version: String): Pair<List<Int>, String?> {
        // 先去掉 tag 前缀：上游最新 release 的 tag 是 "tv2.2.7"，旧版
        // "V3.0.0.238" 还是大写 V——只去 "v"（解析器的做法）会把
        // 首段算成 0，2.2.7 反而比 1.4.4 小，更新检查永远报无更新；
        // 先 trim 两端空白，否则尾部空白会让最后一段 toIntOrNull 得
        // null 记 0，"1.4.4 " 就被算成 1.4.0
        val stripped = version.trim().trimStart { !it.isDigit() }
        // 构建元数据（+ 之后的部分）不参与比较：按 semver 应忽略；不去
        // 的话 "1.4.4-rc.1+build.5" 会比 "1.4.4-rc.1" 大，两个同版本
        // 构建会被误判成有更新
        val mainParts = stripped.substringBefore('+').split("-", limit = 2)
        // 非数字段（如误入的 "v1"）记 0 而不是抛 NumberFormatException
        // 导致整个更新检查失败
        val versionNumbers = mainParts[0].split(".").map { it.toIntOrNull() ?: 0 }
        val preReleaseLabel = if (mainParts.size > 1) mainParts[1] else null
        return versionNumbers to preReleaseLabel
    }

    fun comparePreRelease(label1: String?, label2: String?): Int {
        if (label1 == null && label2 == null) return 0
        if (label1 == null) return 1 // Non-pre-release version is greater
        if (label2 == null) return -1 // Non-pre-release version is greater

        // 按点分段逐段比：纯数字段按数值比（rc.10 > rc.2，字典序会算反），
        // 数字段小于字母段，字母段按字典序；短的若是前缀则更小
        val parts1 = label1.split(".")
        val parts2 = label2.split(".")
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: return -1
            val p2 = parts2.getOrNull(i) ?: return 1
            if (p1 == p2) continue
            val n1 = p1.toIntOrNull()
            val n2 = p2.toIntOrNull()
            if (n1 != null && n2 != null) return n1.compareTo(n2)
            if (n1 != null) return -1
            if (n2 != null) return 1
            return p1.compareTo(p2)
        }
        return 0
    }

    val (v1, preRelease1) = parseVersion(this)
    val (v2, preRelease2) = parseVersion(version2)
    val maxLength = maxOf(v1.size, v2.size)

    for (i in 0 until maxLength) {
        val part1 = v1.getOrElse(i) { 0 }
        val part2 = v2.getOrElse(i) { 0 }
        if (part1 > part2) return 1
        if (part1 < part2) return -1
    }

    // If main version parts are equal, compare pre-release labels
    return comparePreRelease(preRelease1, preRelease2)
}