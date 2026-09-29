package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.SongType
import org.json.JSONArray
import java.text.Normalizer

data class JapaneseChartKey(val title: String, val type: SongType, val difficulty: Int)
data class JapaneseConstant(val value: Double, val notes: Int?)

/** JP-only fields: never read international overrides or infer a decimal from the displayed level. */
class JapaneseConstantCatalog private constructor(
    val values: Map<JapaneseChartKey, JapaneseConstant>,
    val latestSongDate: String?,
) {
    fun find(chart: ChartRecord): Double? {
        val entry = values[JapaneseChartKey(normalize(chart.title), chart.songType, chart.levelIndex)] ?: return null
        // A renewed JP chart may differ from the CN chart despite having the same name.
        val localNotes = chart.notes?.total
        if (entry.notes != null && localNotes != null && entry.notes != localNotes) return null
        return entry.value
    }

    companion object {
        private fun normalize(title: String) = Normalizer.normalize(title.trim(), Normalizer.Form.NFC)
        fun parse(json: String): JapaneseConstantCatalog {
            val rows = JSONArray(json)
            val values = mutableMapOf<JapaneseChartKey, JapaneseConstant>()
            val seen = mutableSetOf<JapaneseChartKey>()
            val ambiguous = mutableSetOf<JapaneseChartKey>()
            var latest: String? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val title = normalize(row.optString("title"))
                if (title.isBlank() || row.has("lev_utage")) continue
                val date = row.optString("date_added")
                if (date.matches(Regex("20[0-9]{6}")) && (latest == null || date > latest)) latest = date
                for ((type, prefix) in listOf(SongType.STANDARD to "lev_", SongType.DX to "dx_lev_")) {
                    for ((index, suffix) in listOf("bas", "adv", "exp", "mas", "remas").withIndex()) {
                        val field = prefix + suffix
                        if (row.optString(field).isBlank()) continue
                        val key = JapaneseChartKey(title, type, index)
                        if (!seen.add(key)) ambiguous += key
                        val value = row.optString("${field}_i").toDoubleOrNull() ?: continue
                        if (!value.isFinite() || value !in 1.0..20.0 || kotlin.math.abs(value * 10 - kotlin.math.round(value * 10)) > 0.00001) continue
                        values[key] = JapaneseConstant(value, row.optString("${field}_notes").toIntOrNull()?.takeIf { it > 0 })
                    }
                }
            }
            ambiguous.forEach(values::remove)
            require(values.isNotEmpty()) { "日服数据没有可用详细定数" }
            return JapaneseConstantCatalog(values, latest)
        }
    }
}
