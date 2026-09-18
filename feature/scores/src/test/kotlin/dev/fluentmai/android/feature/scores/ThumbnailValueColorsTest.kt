package dev.fluentmai.android.feature.scores

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.fluentmai.android.core.model.ChartNotes
import dev.fluentmai.android.core.model.sssPlusTapGreatTolerance
import org.junit.Assert.*
import org.junit.Test

class ThumbnailValueColorsTest {
    @Test fun quadraticStrengthIsSymmetricAndLeavesYellowQuickly() {
        assertEquals(0f, thumbnailColorStrength(0f), .00001f)
        assertEquals(.302169f, thumbnailColorStrength(.05f), .00001f)
        assertEquals(.75f, thumbnailColorStrength(.22f), .00001f)
        assertEquals(1f, thumbnailColorStrength(1f), .00001f)
        assertEquals(1f, thumbnailColorStrength(2f), .00001f)
        for (step in 0..100) {
            val distance = step / 100f
            assertEquals(thumbnailColorStrength(distance), thumbnailColorStrength(-distance), 0f)
            if (step > 0) assertTrue(thumbnailColorStrength(distance) > thumbnailColorStrength((step - 1) / 100f))
        }
        assertEquals(thumbnailColorStrength(.22f - .00001f), thumbnailColorStrength(.22f + .00001f), .00001f)
        for (light in listOf(true, false)) {
            val palette = thumbnailValuePalette(light)
            for (sign in listOf(-1f, 1f)) {
                val target = if (sign < 0) palette.red else palette.green
                assertEquals(androidx.compose.ui.graphics.lerp(palette.yellow, target, thumbnailColorStrength(.05f)),
                    thumbnailValueColor(sign * .05f, light, Color.Gray))
            }
        }
    }

    @Test fun longSongDoesNotChangeToleranceRangeButStillHasItsOwnValueAndColor() {
        val low = previewChart().copy(notes = ChartNotes(110, 100, 0, 0, 0, 10))
        val high = previewChart().copy(notes = ChartNotes(1110, 1100, 0, 0, 0, 10))
        val normalScale = ThumbnailValueScale.fromCharts(listOf(low, high))
        val longCharts = dev.fluentmai.android.core.model.Difficulty.entries.mapIndexed { index, difficulty ->
            high.copy(title = "Xaleid◆scopiX", difficulty = difficulty, levelIndex = index,
                notes = ChartNotes(10010, 10000, 0, 0, 0, 10), fittedConstant = 16.3)
        }
        val scale = ThumbnailValueScale.fromCharts(listOf(low, high) + longCharts)
        assertEquals(normalScale.minimumTolerance, scale.minimumTolerance)
        assertEquals(normalScale.maximumTolerance, scale.maximumTolerance)
        assertEquals(normalScale.position(ThumbnailField.Tolerance, low), scale.position(ThumbnailField.Tolerance, low))
        assertEquals(normalScale.position(ThumbnailField.Tolerance, high), scale.position(ThumbnailField.Tolerance, high))
        for (chart in longCharts) {
            assertTrue(requireNotNull(chart.sssPlusTapGreatTolerance()) > scale.maximumTolerance)
            assertEquals(1f, scale.position(ThumbnailField.Tolerance, chart)!!, 0f)
        }
        // Only the tolerance reference excludes the song, not fitted-gap colors.
        assertEquals(2.0, scale.maximumFitGap, .00001)
        val onlyLong = ThumbnailValueScale.fromCharts(longCharts)
        assertEquals(0, onlyLong.minimumTolerance)
        assertEquals(100, onlyLong.maximumTolerance)
        // A low-tolerance difficulty of the same song must not pull down the minimum either.
        val longLow = low.copy(title = " Xaleid◆scopiX ", notes = ChartNotes(2, 1, 0, 0, 0, 1))
        assertTrue(requireNotNull(longLow.sssPlusTapGreatTolerance()) < normalScale.minimumTolerance)
        assertEquals(normalScale.minimumTolerance,
            ThumbnailValueScale.fromCharts(listOf(low, high, longLow)).minimumTolerance)
    }

    @Test fun gapUsesOfficialMinusFittedAndYellowForDisplayedZero() {
        val chart = previewChart().copy(levelValue = 14.0)
        val scale = ThumbnailValueScale(maximumFitGap = 1.0)
        assertEquals(-1f, scale.position(ThumbnailField.FitGap, chart.copy(fittedConstant = 15.0))!!, .00001f)
        assertEquals(-.5f, scale.position(ThumbnailField.FitGap, chart.copy(fittedConstant = 14.5))!!, .00001f)
        assertEquals(0f, scale.position(ThumbnailField.FitGap, chart.copy(fittedConstant = 14.00001))!!, .00001f)
        assertEquals(.5f, scale.position(ThumbnailField.FitGap, chart.copy(fittedConstant = 13.5))!!, .00001f)
        assertEquals(1f, scale.position(ThumbnailField.FitGap, chart.copy(fittedConstant = 13.0))!!, .00001f)
    }

    @Test fun missingDataAndOtherFieldsKeepNormalTextColor() {
        val scale = ThumbnailValueScale()
        assertNull(scale.position(ThumbnailField.FitGap, previewChart().copy(fittedConstant = null)))
        assertNull(scale.position(ThumbnailField.FitGap, previewChart().copy(fittedConstant = Double.NaN)))
        assertNull(scale.position(ThumbnailField.Tolerance, previewChart().copy(notes = null)))
        assertNull(scale.position(ThumbnailField.Constant, previewChart()))
        assertEquals(Color.Gray, thumbnailValueColor(null, true, Color.Gray))
    }

    @Test fun wholeCatalogToleranceRangeHasRedMinimumYellowMidpointAndGreenMaximum() {
        val low = previewChart().copy(notes = ChartNotes(110, 100, 0, 0, 0, 10), fittedConstant = 14.8)
        val high = previewChart().copy(notes = ChartNotes(1110, 1100, 0, 0, 0, 10), fittedConstant = 13.8)
        val scale = ThumbnailValueScale.fromCharts(listOf(low, high))
        assertEquals(-1f, scale.position(ThumbnailField.Tolerance, low)!!, .00001f)
        assertEquals(1f, scale.position(ThumbnailField.Tolerance, high)!!, .00001f)
        val tolerance = requireNotNull(low.sssPlusTapGreatTolerance())
        assertEquals(0f, ThumbnailValueScale(minimumTolerance = tolerance - 2, maximumTolerance = tolerance + 2)
            .position(ThumbnailField.Tolerance, low)!!, .00001f)
        assertEquals(0f, ThumbnailValueScale.fromCharts(listOf(low, low)).position(ThumbnailField.Tolerance, low)!!, .00001f)
        assertEquals(.5, scale.maximumFitGap, .00001)
    }

    @Test fun palettesAreContinuousAndReadableOnBothThemes() {
        for (light in listOf(true, false)) {
            val palette = thumbnailValuePalette(light)
            assertEquals(palette.red, thumbnailValueColor(-1f, light, Color.Gray))
            assertEquals(palette.yellow, thumbnailValueColor(0f, light, Color.Gray))
            assertEquals(palette.green, thumbnailValueColor(1f, light, Color.Gray))
            val colors = (-10..10).map { thumbnailValueColor(it / 10f, light, Color.Gray) }
            assertEquals(21, colors.distinct().size)
            val background = if (light) Color(0xFFF3F6F8) else Color(0xFF141B20)
            for (color in colors) {
                val l1 = maxOf(color.luminance(), background.luminance())
                val l2 = minOf(color.luminance(), background.luminance())
                assertTrue("Readable contrast: $color", (l1 + .05f) / (l2 + .05f) >= 4.5f)
            }
        }
    }
}
