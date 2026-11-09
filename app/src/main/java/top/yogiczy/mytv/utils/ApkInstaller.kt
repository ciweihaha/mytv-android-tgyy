package top.yogiczy.mytv.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {
    @SuppressLint("SetWorldReadable")
    fun installApk(context: Context, filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            val cacheDir = context.cacheDir
            val cachedApkFile = File(cacheDir, file.name)
            // 文件已在缓存目录时（自更新与局域网上传的两个调用方都是如此），
            // 跳过自我复制；否则流式复制：原先 writeBytes(file.readBytes())
            // 会把整个 APK 读进内存再写回，在低内存电视盒子上容易 OOM
            if (cachedApkFile.absolutePath != file.absolutePath) {
                file.copyTo(cachedApkFile, overwrite = true)
            }
            // 解决Android6 无法解析安装包
            cachedApkFile.setReadable(true, false)

            val uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) FileProvider.getUriForFile(
                    context, context.packageName + ".FileProvider", cachedApkFile
                )
                else Uri.fromFile(cachedApkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                setDataAndType(uri, "application/vnd.android.package-archive")
            }

            context.startActivity(installIntent)
        }
    }
}