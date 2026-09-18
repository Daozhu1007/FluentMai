package dev.fluentmai.android.feature.scores

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.sssPlusTapGreatTolerance
import kotlin.math.abs
import kotlin.math.round

/** Use the entire catalog, never the filtered results, so filtering cannot change a chart's color. */
internal data class ThumbnailValueScale(
    val maximumFitGap: Double = 1.0,
    val minimumTolerance: Int = 0,
    val maximumTolerance: Int = 100,
) {
    fun position(field: ThumbnailField, chart: ChartRecord): Float? = when (field) {
        ThumbnailField.FitGap -> chart.visibleFitGap()?.let {
            (it / (maximumFitGap.takeIf { scale -> scale.isFinite() && scale > 0 } ?: 1.0)).toFloat().coerceIn(-1f, 1f)
        }
        ThumbnailField.Tolerance -> chart.sssPlusTapGreatTolerance()?.let { tolerance ->
            if (minimumTolerance == maximumTolerance) 0f
            else (2.0 * (tolerance.toDouble() - minimumTolerance) / (maximumTolerance.toDouble() - minimumTolerance) - 1.0)
                .toFloat().coerceIn(-1f, 1f)
        }
        else -> null
    }

    companion object {
        fun fromCharts(charts: List<ChartRecord>): ThumbnailValueScale {
            val gaps = charts.mapNotNull { it.visibleFitGap()?.let(::abs) }
            // The catalog currently has no two-track/long-song flag. Exclude this known
            // long song from the reference range only; its own value/color still renders.
            val tolerances = charts.filterNot { it.title.trim().equals("Xaleid◆scopiX", ignoreCase = true) }
                .mapNotNull { it.sssPlusTapGreatTolerance() }
            return ThumbnailValueScale(
                maximumFitGap = gaps.maxOrNull()?.takeIf { it > 0 } ?: 1.0,
                minimumTolerance = tolerances.minOrNull() ?: 0,
                maximumTolerance = tolerances.maxOrNull() ?: 100,
            )
        }
    }
}

// Color the displayed four-decimal value, so a displayed zero is always yellow.
private fun ChartRecord.visibleFitGap(): Double? {
    val official = levelValue?.takeIf { it.isFinite() } ?: return null
    val fitted = fittedConstant?.takeIf { it.isFinite() } ?: return null
    return round((official - fitted) * 10_000) / 10_000
}

internal data class ThumbnailValuePalette(val red: Color, val yellow: Color, val green: Color)

internal fun thumbnailValuePalette(lightTheme: Boolean) = if (lightTheme) {
    // Deeper gold/green/red remain readable on the light chart surface.
    ThumbnailValuePalette(Color(0xFFBD3030), Color(0xFF896600), Color(0xFF19733D))
} else {
    ThumbnailValuePalette(Color(0xFFFF8686), Color(0xFFFFD65A), Color(0xFF80DCA0))
}

internal fun thumbnailValueColor(position: Float?, lightTheme: Boolean, fallback: Color): Color {
    if (position == null || !position.isFinite()) return fallback
    val palette = thumbnailValuePalette(lightTheme)
    val target = if (position < 0) palette.red else palette.green
    val amount = thumbnailColorStrength(position)
    return when (amount) {
        0f -> palette.yellow
        1f -> target
        else -> lerp(palette.yellow, target, amount)
    }
}

/** Mirrored piecewise quadratics: leave yellow quickly, then keep deepening toward either end. */
internal fun thumbnailColorStrength(position: Float): Float {
    val distance = abs(position).coerceIn(0f, 1f)
    fun easeOutQuadratic(value: Float): Float = value * (2f - value)
    // At 5% from the midpoint, use 30% red/green; at 22%, use 75%.
    // Gentler than the previous 44% / 80% curve, while retaining a narrow yellow region.
    return if (distance <= .22f) {
        .75f * easeOutQuadratic(distance / .22f)
    } else {
        val outerDistance = (distance - .22f) / .78f
        // Keep a nonzero final slope so 8-bit colors do not flatten near the extrema.
        .75f + .25f * outerDistance * (1.5f - .5f * outerDistance)
    }
}
