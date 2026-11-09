package top.yogiczy.mytv.data.entities

/**
 * git版本
 */
data class GitRelease(
    val version: String = "0.0.0",
    val downloadUrl: String = "",
    val description: String = "",
    /** 安装包字节数：-1 表示发行版信息里没有（不校验） */
    val downloadSize: Long = -1,
)
