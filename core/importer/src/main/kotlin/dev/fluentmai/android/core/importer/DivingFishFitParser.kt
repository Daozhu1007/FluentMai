package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.fittedChartKey
import org.json.JSONObject

object DivingFishFitParser {
    fun parse(json: String): Map<String, Double> = buildMap {
        val charts = JSONObject(json).getJSONObject("charts")
        charts.keys().forEach { idText ->
            val id = idText.toIntOrNull() ?: return@forEach
            if (id !in 0..19999) return@forEach
            val type = if (id >= 10000) SongType.DX else SongType.STANDARD
            val entries = charts.optJSONArray(idText) ?: return@forEach
            // Empty objects occupy a difficulty slot and must not be removed.
            for (index in 0 until minOf(entries.length(), 5)) {
                val value = entries.optJSONObject(index)?.optDouble("fit_diff", Double.NaN) ?: continue
                if (value.isFinite() && value > 0) put(fittedChartKey(id % 10000, type, index), value)
            }
        }
    }
}
