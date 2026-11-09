package top.yogiczy.mytv.utils

fun Long.humanizeMs(): String {
    return when (this) {
        in 0..<60_000 -> "${this / 1000}秒"
        in 60_000..<3_600_000 -> "${this / 60_000}分钟"
        else -> "${this / 3_600_000}小时"
    }
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
        // 首段算成 0，2.2.7 反而比 1.4.4 小，更新检查永远报无更新
        val stripped = version.trimStart { !it.isDigit() }
        val mainParts = stripped.split("-", limit = 2)
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