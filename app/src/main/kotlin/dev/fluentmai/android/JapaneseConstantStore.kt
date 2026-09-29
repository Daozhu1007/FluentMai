package dev.fluentmai.android

import android.content.Context
import android.util.AtomicFile
import dev.fluentmai.android.core.importer.JapaneseConstantCatalog
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class JapaneseConstantSnapshot(
    val catalog: JapaneseConstantCatalog,
    val checkedAt: Long,
    val sourceModifiedAt: Long,
    val endpoint: String,
    val etag: String,
    val json: String,
)

internal data class JapaneseConstantResponse(val code: Int, val body: String = "", val etag: String = "", val modifiedAt: Long = 0)

/** Two official distribution endpoints of the same community dataset; no user credentials are sent. */
internal class JapaneseConstantStore(
    context: Context,
    private val fetch: (String, String?) -> JapaneseConstantResponse = ::fetchJapaneseConstants,
    private val now: () -> Long = System::currentTimeMillis,
    private val minimumEntries: Int = 1000,
) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "japanese-chart-constants.json"))

    @Synchronized fun cached(): JapaneseConstantSnapshot? = runCatching {
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val data = root.getString("data")
        JapaneseConstantSnapshot(JapaneseConstantCatalog.parse(data), root.getLong("checkedAt"),
            root.optLong("sourceModifiedAt"), root.getString("endpoint"), root.optString("etag"), data)
    }.getOrNull()

    @Synchronized fun refresh(): JapaneseConstantSnapshot {
        val previous = cached()
        for (endpoint in ENDPOINTS) {
            try {
                val response = fetch(endpoint, previous?.takeIf { it.endpoint == endpoint }?.etag?.takeIf { it.isNotBlank() })
                val snapshot = when (response.code) {
                    304 -> {
                        require(previous != null && previous.endpoint == endpoint) { "无对应缓存" }
                        previous.copy(checkedAt = now())
                    }
                    200 -> {
                        val catalog = JapaneseConstantCatalog.parse(response.body)
                        require(catalog.values.size >= minimumEntries) { "数据过少" }
                        if (previous != null) {
                            require(catalog.values.size * 100L >= previous.catalog.values.size * 80L) { "拒绝残缺数据" }
                            require(response.modifiedAt == 0L || previous.sourceModifiedAt == 0L || response.modifiedAt >= previous.sourceModifiedAt) { "拒绝旧数据" }
                            require(previous.catalog.latestSongDate == null || (catalog.latestSongDate ?: "") >= previous.catalog.latestSongDate!!) { "曲库日期倒退" }
                        }
                        JapaneseConstantSnapshot(catalog, now(), response.modifiedAt, endpoint, response.etag, response.body)
                    }
                    else -> throw IOException("HTTP ${response.code}")
                }
                save(snapshot)
                return snapshot
            } catch (_: Exception) {
                // Reject HTML, partial datasets and failed mirrors without replacing the good cache.
            }
        }
        throw IOException("日服数据暂时无法同步，已保留上次数据")
    }

    private fun save(snapshot: JapaneseConstantSnapshot) {
        val bytes = JSONObject().put("data", snapshot.json).put("checkedAt", snapshot.checkedAt)
            .put("sourceModifiedAt", snapshot.sourceModifiedAt).put("endpoint", snapshot.endpoint)
            .put("etag", snapshot.etag).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        val ENDPOINTS = listOf("https://otoge-db.net/maimai/data/music-ex.json",
            "https://raw.githubusercontent.com/zvuc/otoge-db/main/maimai/data/music-ex.json")
    }
}

private fun fetchJapaneseConstants(endpoint: String, etag: String?): JapaneseConstantResponse {
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        if (etag != null) connection.setRequestProperty("If-None-Match", etag)
        val code = connection.responseCode
        val body = if (code == 200) connection.inputStream.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "日服数据超过大小限制" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        } else ""
        return JapaneseConstantResponse(code, body, connection.getHeaderField("ETag").orEmpty(), connection.lastModified)
    } finally { connection.disconnect() }
}
