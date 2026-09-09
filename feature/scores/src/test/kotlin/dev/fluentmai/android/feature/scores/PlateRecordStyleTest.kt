package dev.fluentmai.android.feature.scores

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateRecordStyleTest {
    @Test
    fun completedAndIncompleteCardsUseThemeSpecificBackgrounds() {
        assertEquals(Color(0xFFE1F2E7), plateRecordBackgroundColor(completed = true, lightTheme = true))
        assertEquals(Color(0xFFFBE5E5), plateRecordBackgroundColor(completed = false, lightTheme = true))
        assertEquals(Color(0xFF193C30), plateRecordBackgroundColor(completed = true, lightTheme = false))
        assertEquals(Color(0xFF45282D), plateRecordBackgroundColor(completed = false, lightTheme = false))
    }

    @Test
    fun neutralThemeTextIsReadableOnEveryStatusBackground() {
        for (lightTheme in listOf(true, false)) {
            val text = if (lightTheme) Color(0xFF172027) else Color(0xFFE7ECEF)
            val secondaryText = if (lightTheme) Color(0xFF52616C) else Color(0xFFC1CBD3)
            for (completed in listOf(true, false)) {
                val background = plateRecordBackgroundColor(completed, lightTheme)
                for (foreground in listOf(text, secondaryText)) {
                    val contrast = (maxOf(foreground.luminance(), background.luminance()) + 0.05f) /
                        (minOf(foreground.luminance(), background.luminance()) + 0.05f)
                    assertTrue("Text contrast must be at least 4.5:1", contrast >= 4.5f)
                }
                assertEquals(lightTheme, background.luminance() > 0.5f)
            }
        }
    }
}
