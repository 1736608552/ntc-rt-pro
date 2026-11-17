package com.ntcrt.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CdnMirror(
    val id: String,
    val name: String,
    val prefix: String,
    var latencyMs: Long = -1L,
    var available: Boolean = false
) {
    fun wrapUrl(rawGithubUrl: String): String {
        return if (prefix.isEmpty()) rawGithubUrl else prefix + rawGithubUrl
    }
}

data class RemoteUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkName: String,
    val apkSize: Long,
    val releaseNotes: String,
    val publishedAt: String,
    val rawApkUrl: String,
    val mirrors: List<CdnMirror>
) {
    val hasNewVersion: Boolean
        get() = versionCode > BuildConfig.VERSION_CODE
}

/**
 * 多 CDN 加速在线更新管理器（支持并发节点测速、自动优选与断点容错切换）
 */
object AppUpdater {

    private const val OWNER = "1736608552"
    private const val REPO = "ntc-rt-pro"
    private const val VERSION_JSON_ORIGIN =
        "https://github.com/$OWNER/$REPO/releases/latest/download/version.json"
    private const val API_LATEST_ORIGIN =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    fun createMirrors(): List<CdnMirror> = listOf(
        CdnMirror("ghfast", "GHFast 极速 CDN", "https://ghfast.top/"),
        CdnMirror("ghproxy_com", "GH-Proxy 加速", "https://gh-proxy.com/"),
        CdnMirror("mirror_ghproxy", "Mirror GHProxy", "https://mirror.ghproxy.com/"),
        CdnMirror("ghproxy_net", "GHProxy.net 镜像", "https://ghproxy.net/"),
        CdnMirror("gh_llkk", "LLKK 加速节点", "https://gh.llkk.cc/"),
        CdnMirror("direct", "GitHub 官方直连", "")
    )

    /**
     * 多 CDN 并发测速并检测最新版本
     */
    fun checkForUpdates(
        onSuccess: (RemoteUpdateInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        ioExecutor.execute {
            try {
                val mirrors = createMirrors()
                val tasks = mirrors.map { mirror ->
                    Callable {
                        val t0 = System.currentTimeMillis()
                        val url = mirror.wrapUrl(VERSION_JSON_ORIGIN)
                        val body = httpGetString(url, timeoutMs = 6000)
                        val json = JSONObject(body)
                        val vc = json.optInt("versionCode", 0)
                        if (vc <= 0) error("Invalid version.json")
                        mirror.latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        mirror.available = true
                        json
                    }
                }

                val pool = Executors.newFixedThreadPool(mirrors.size)
                var parsedJson: JSONObject? = null
                try {
                    val futures = pool.invokeAll(tasks, 6500, TimeUnit.MILLISECONDS)
                    for (f in futures) {
                        if (f.isDone && !f.isCancelled) {
                            try {
                                val res = f.get()
                                if (parsedJson == null) parsedJson = res
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } finally {
                    pool.shutdownNow()
                }

                // 若所有 version.json 均未成功，回退官方 Release API 兜底
                val finalInfo = if (parsedJson != null) {
                    val apkName = parsedJson.optString("apkName", "NTC-RT-Pro-v${parsedJson.optString("versionName")}.apk")
                    val tag = "v${parsedJson.optString("versionName", "1.0.0")}"
                    val rawApkUrl = parsedJson.optString(
                        "downloadUrl",
                        "https://github.com/$OWNER/$REPO/releases/download/$tag/$apkName"
                    )
                    RemoteUpdateInfo(
                        versionCode = parsedJson.optInt("versionCode", 0),
                        versionName = parsedJson.optString("versionName", "1.0.0"),
                        apkName = apkName,
                        apkSize = parsedJson.optLong("apkSize", 0L),
                        releaseNotes = parsedJson.optString("releaseNotes", ""),
                        publishedAt = parsedJson.optString("publishedAt", ""),
                        rawApkUrl = rawApkUrl,
                        mirrors = mirrors.sortedWith(
                            compareByDescending<CdnMirror> { it.available }
                                .thenBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
                        )
                    )
                } else {
                    fetchFromGithubApiFallback(mirrors)
                }

                mainHandler.post { onSuccess(finalInfo) }
            } catch (e: Throwable) {
                mainHandler.post {
                    onError(e.message ?: "检查更新失败，请检查网络连接")
                }
            }
        }
    }

    private fun fetchFromGithubApiFallback(mirrors: List<CdnMirror>): RemoteUpdateInfo {
        val t0 = System.currentTimeMillis()
        val body = httpGetString(API_LATEST_ORIGIN, timeoutMs = 8000)
        val obj = JSONObject(body)
        mirrors.lastOrNull { it.id == "direct" }?.apply {
            available = true
            latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        }
        val tagName = obj.optString("tag_name", "v1.0.0")
        val verName = tagName.removePrefix("v")
        val assets = obj.optJSONArray("assets")
        var apkName = "NTC-RT-Pro-$tagName.apk"
        var apkSize = 0L
        var rawApkUrl = "https://github.com/$OWNER/$REPO/releases/latest/download/$apkName"
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkName = name
                    apkSize = a.optLong("size", 0L)
                    rawApkUrl = a.optString("browser_download_url", rawApkUrl)
                    break
                }
            }
        }
        val vc = parseVersionCode(verName)
        return RemoteUpdateInfo(
            versionCode = vc,
            versionName = verName,
            apkName = apkName,
            apkSize = apkSize,
            releaseNotes = obj.optString("body", "版本更新与体验优化"),
            publishedAt = obj.optString("published_at", ""),
            rawApkUrl = rawApkUrl,
            mirrors = mirrors.sortedWith(
                compareByDescending<CdnMirror> { it.available }
                    .thenBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
            )
        )
    }

    private fun parseVersionCode(verName: String): Int {
        val parts = verName.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val major = parts.getOrElse(0) { 1 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 100 + minor * 10 + patch
    }

    /**
     * 下载 APK（优先从指定 CDN 下载，失败自动顺位切换下一可用 CDN）
     */
    fun downloadApkWithFallback(
        context: Context,
        info: RemoteUpdateInfo,
        preferredMirror: CdnMirror,
        cancelFlag: AtomicBoolean,
        onMirrorSwitched: (CdnMirror) -> Unit,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speedText: String) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        ioExecutor.execute {
            val orderedMirrors = buildList {
                add(preferredMirror)
                info.mirrors.forEach { if (it.id != preferredMirror.id) add(it) }
            }
            val dir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(dir, "NTC-RT-Pro-update.apk")
            var lastErr = "所有加速节点下载均失败"

            for (mirror in orderedMirrors) {
                if (cancelFlag.get()) {
                    mainHandler.post { onError("已取消下载") }
                    return@execute
                }
                mainHandler.post { onMirrorSwitched(mirror) }
                val tmpFile = File(dir, "NTC-RT-Pro-update.apk.part")
                if (tmpFile.exists()) tmpFile.delete()

                try {
                    val downloadUrl = mirror.wrapUrl(info.rawApkUrl)
                    downloadSingleUrl(
                        urlStr = downloadUrl,
                        destFile = tmpFile,
                        expectedSize = info.apkSize,
                        cancelFlag = cancelFlag,
                        onProgress = { pct, cur, tot, spd ->
                            mainHandler.post { onProgress(pct, cur, tot, spd) }
                        }
                    )
                    if (isValidApkFile(tmpFile)) {
                        if (targetFile.exists()) targetFile.delete()
                        if (!tmpFile.renameTo(targetFile)) {
                            tmpFile.copyTo(targetFile, overwrite = true)
                            tmpFile.delete()
                        }
                        mainHandler.post { onComplete(targetFile) }
                        return@execute
                    } else {
                        lastErr = "${mirror.name} 返回无效安装包内容"
                        tmpFile.delete()
                    }
                } catch (e: Throwable) {
                    tmpFile.delete()
                    if (cancelFlag.get()) {
                        mainHandler.post { onError("已取消下载") }
                        return@execute
                    }
                    lastErr = "${mirror.name}: ${e.message ?: "连接失败"}"
                }
            }
            mainHandler.post { onError(lastErr) }
        }
    }

    private fun downloadSingleUrl(
        urlStr: String,
        destFile: File,
        expectedSize: Long,
        cancelFlag: AtomicBoolean,
        onProgress: (Int, Long, Long, String) -> Unit
    ) {
        val conn = openFollowRedirects(urlStr, timeoutMs = 15_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var downloaded = 0L
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelFlag.get()) error("已取消")
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val dt = now - lastReportTime
                        if (dt >= 250) {
                            val bytesPerSec = ((downloaded - lastReportBytes) * 1000.0) / dt.coerceAtLeast(1L)
                            val spd = formatSpeed(bytesPerSec)
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                            onProgress(pct, downloaded, total, spd)
                            lastReportTime = now
                            lastReportBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }
            onProgress(100, downloaded, total.coerceAtLeast(downloaded), "完成")
        } finally {
            conn.disconnect()
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 512 * 1024) return false
        return file.inputStream().use { ins ->
            val b0 = ins.read()
            val b1 = ins.read()
            b0 == 0x50 && b1 == 0x4B // 'P''K' ZIP/APK 文件头
        }
    }

    private fun httpGetString(urlStr: String, timeoutMs: Int): String {
        val conn = openFollowRedirects(urlStr, timeoutMs)
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowRedirects(urlStr: String, timeoutMs: Int): HttpURLConnection {
        var currentUrl = urlStr
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs + 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "NTC-RT-Pro-Updater/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) error("重定向地址为空")
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                return conn
            }
        }
        error("重定向次数过多")
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知大小"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
