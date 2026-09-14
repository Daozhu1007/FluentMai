package dev.fluentmai.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*

internal object AppUpdateClient {
    const val API = "https://api.github.com/repos/Daozhu1007/FluentMai/releases?per_page=30"
    const val MIRROR = "https://gh-proxy.org/"

    suspend fun newest(): AppRelease? = coroutineScope {
        listOf(API, MIRROR + API).map { url -> async(Dispatchers.IO) {
            try {
                val connection = connect(url, 5_000)
                try {
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 2_000_000)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 2_000_000) emptyList() else parseAppReleases(bytes.toString(Charsets.UTF_8))
                } finally { connection.disconnect() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } }.awaitAll().flatten().maxByOrNull { it.version }
    }

    suspend fun download(context: Context, release: AppRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(isOfficialApkUrl(release.assetUrl))
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        for (url in listOf(release.assetUrl, MIRROR + release.assetUrl)) {
            val file = File.createTempFile("FluentMai-", ".apk", directory)
            try {
                progress(0f)
                val connection = connect(url, 12_000)
                try {
                    var total = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 200L * 1024 * 1024) { "安装包超出大小限制" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (release.size > 0) progress((total.toFloat() / release.size).coerceIn(0f, 1f))
                        }
                    } }
                    check(total > 0 && (release.size <= 0 || total == release.size)) { "安装包不完整" }
                    release.sha256?.let { expected ->
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        check(actual.equals(expected, true)) { "安装包校验失败" }
                    }
                } finally { connection.disconnect() }
                validateApk(context, file, release.version)
                return@withContext file
            } catch (cancelled: CancellationException) { file.delete(); throw cancelled }
            catch (_: Exception) { file.delete() }
        }
        throw IOException("下载或安全校验失败，请检查网络后重试。")
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(context: Context, file: File, expected: AppVersion) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming = requireNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "无法读取安装包" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(incoming.packageName == context.packageName) { "软件包名不符" }
        fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(version(incoming) > version(installed) && AppVersion.parse(incoming.versionName) == expected) { "安装包版本不符" }
        fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            else info.signatures?.toList().orEmpty()
        val trusted = signers(installed)
        val received = signers(incoming)
        check(trusted.isNotEmpty() && received.size == trusted.size && received.all { it in trusted }) { "安装包签名不符" }
    }

    private fun connect(initial: String, timeout: Int): HttpURLConnection {
        var url = initial
        repeat(6) {
            check(URI(url).scheme == "https") { "不安全的下载地址" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FluentMai-Android-Updater")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URI(url).resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
                    connection.disconnect()
                } else {
                    if (status !in 200..299) throw IOException("HTTP $status")
                    return connection
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("重定向过多")
    }
}
