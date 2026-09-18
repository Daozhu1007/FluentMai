package dev.fluentmai.android

import android.content.Context
import android.util.AtomicFile
import dev.fluentmai.android.core.importer.DivingFishFitParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class FittedSnapshot(val values: Map<String, Double>, val updatedAt: Long)

internal class DivingFishFitStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "diving-fish-fitted.json"))
    fun cached(): FittedSnapshot? = runCatching {
        val root = org.json.JSONObject(file.openRead().bufferedReader().use { it.readText() })
        FittedSnapshot(DivingFishFitParser.parse(root.getString("data")), root.getLong("updatedAt"))
    }.getOrNull()

    fun refresh(): FittedSnapshot {
        val connection = URL("https://www.diving-fish.com/api/maimaidxprober/chart_stats").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "Fitted constants unavailable" }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val values = DivingFishFitParser.parse(json)
            check(values.isNotEmpty()) { "Empty fitted constants" }
            val now = System.currentTimeMillis()
            val data = org.json.JSONObject().put("updatedAt", now).put("data", json).toString().toByteArray()
            val stream = file.startWrite()
            try { stream.write(data); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            return FittedSnapshot(values, now)
        } finally { connection.disconnect() }
    }
}
