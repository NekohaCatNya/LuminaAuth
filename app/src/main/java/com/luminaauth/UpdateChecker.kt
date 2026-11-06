// 应用内更新：查 GitHub Releases 最新版 → FileDownloader 32 线程分片下载 → 唤起系统安装器 → 启动时清理残留
package com.luminaauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.liulishuo.filedownloader.FileDownloader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val REPO = "NekohaCatNya/LuminaAuth"
    const val REPO_URL = "https://github.com/$REPO"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    /** 查最新 release；返回 null 表示已是最新或没找到可安装的 apk 资源 */
    suspend fun checkLatest(currentVersion: String): Release? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LuminaAuth-App")
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").substringBefore('-').trim()
                if (!isNewer(tag, currentVersion)) return@withContext null
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val u = a.optString("browser_download_url")
                    if (u.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = u
                        size = a.optLong("size")
                        break
                    }
                }
                if (apkUrl == null) null else Release(tag, apkUrl, size)
            } catch (_: Throwable) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    private fun isNewer(tag: String, current: String): Boolean {
        val tp = tag.split(".").mapNotNull { it.toIntOrNull() }
        val cp = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(tp.size, cp.size)
        for (i in 0 until n) {
            val t = tp.getOrElse(i) { 0 }
            val c = cp.getOrElse(i) { 0 }
            if (t > c) return true
            if (t < c) return false
        }
        return false
    }

    /**
     * 用 FileDownloader 下载（32 连接分片并发 + 断点续传）。
     * 回调都在主线程触发。
     */
    fun startDownload(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
        onDone: (File) -> Unit,
        onError: (Throwable) -> Unit,
    ): Int {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val task = FileDownloader.getImpl().create(url)
            .setPath(dest.absolutePath)
            .setCallbackProgressMinInterval(50)
            .setForceReDownload(true)
            .setAutoRetryTimes(3)
            .setListener(object : com.liulishuo.filedownloader.FileDownloadListener() {
                override fun pending(task: com.liulishuo.filedownloader.BaseDownloadTask?, soFarBytes: Int, totalBytes: Int) {}
                override fun progress(task: com.liulishuo.filedownloader.BaseDownloadTask?, soFarBytes: Int, totalBytes: Int) {
                    onProgress(soFarBytes.toLong(), totalBytes.toLong())
                }
                override fun completed(task: com.liulishuo.filedownloader.BaseDownloadTask?) {
                    onDone(dest)
                }
                override fun paused(task: com.liulishuo.filedownloader.BaseDownloadTask?, soFarBytes: Int, totalBytes: Int) {}
                override fun error(task: com.liulishuo.filedownloader.BaseDownloadTask?, e: Throwable?) {
                    onError(e ?: RuntimeException("download failed"))
                }
                override fun warn(task: com.liulishuo.filedownloader.BaseDownloadTask?) {}
            })
        return task.start()
    }

    /** 唤起系统安装器；安装器读取完之前不能删文件，残留 APK 在下次 App 启动时清理 */
    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 清理上次更新残留的 APK（在 App 启动时调用） */
    fun cleanupStale(context: Context) {
        try {
            val dirs = listOfNotNull(context.externalCacheDir, context.cacheDir)
            dirs.forEach { d -> File(d, "update.apk").delete() }
        } catch (_: Throwable) {
        }
    }
}
