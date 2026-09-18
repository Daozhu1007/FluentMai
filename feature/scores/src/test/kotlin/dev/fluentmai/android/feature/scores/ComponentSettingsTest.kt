package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.ChartPlayCount
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentSettingsTest {
    @Test fun thumbnailVersionOmitsOnlyTheAnnualWumengPrefix() {
        for (year in 2020..2030) {
            assertEquals("DX$year", "舞萌DX$year".compactThumbnailVersion())
            assertEquals("DX$year PLUS", "舞萌DX $year PLUS".compactThumbnailVersion())
            assertEquals("DX$year 第三次更新", "舞萌ＤＸ $year 第三次更新".compactThumbnailVersion())
        }
        assertEquals("maimai PLUS", "maimai PLUS".compactThumbnailVersion())
        assertEquals("舞萌DX", "舞萌DX".compactThumbnailVersion())
        val chart = previewChart().copy(chartVersionName = "舞萌DX2026")
        assertEquals("DX2026", ThumbnailField.Version.value(chart, null))
        assertEquals("舞萌DX2026", chart.chartVersionName)
    }

    @Test fun defaultsAndReplacementNeverAllowDuplicateSlots() {
        val defaults = ComponentSettings()
        assertEquals(listOf("定数", "BPM", "版本", "物量"), defaults.thumbnailFields.map { it.label })
        assertEquals(defaults, defaults.replaceSlot(0, ThumbnailField.Bpm))
        assertEquals(defaults, defaults.replaceSlot(-1, ThumbnailField.PlayCount))
        val changed = defaults.replaceSlot(0, ThumbnailField.PlayCount)
        assertEquals(ThumbnailField.PlayCount, changed.thumbnailFields[0])
        assertEquals(4, changed.thumbnailFields.distinct().size)
        assertEquals(defaults, ComponentSettings.decode("Constant,Constant,Bpm,Notes", emptySet()))
        assertEquals(defaults, ComponentSettings.decode("unknown", setOf("unknown")))
    }

    @Test fun fittedGapKeepsNegativeSignAndMissingValuesAreNotZero() {
        val chart = previewChart()
        assertEquals("-0.1200", ThumbnailField.FitGap.value(chart, null))
        assertEquals("0.3000", ThumbnailField.FitGap.value(chart.copy(fittedConstant = 14.0), null))
        assertEquals("--", ThumbnailField.FitGap.value(chart.copy(fittedConstant = null), null))
        assertEquals("14.4200", ThumbnailField.Fitted.value(chart, null))
        val bound = ChartPlayCount(chart.title, chart.songType, chart.difficulty, 5, true)
        assertEquals("≤5", ThumbnailField.PlayCount.value(chart, null, bound))
        assertEquals("5", ThumbnailField.PlayCount.value(chart, null, bound.copy(isUpperBound = false)))
        assertEquals("--", ThumbnailField.PlayCount.value(chart, null))
    }

    @Test fun customizationPersistsAndResetDoesNotTouchFavoritesOrCredentials() {
        val context = RuntimeEnvironment.getApplication()
        val favorites = context.getSharedPreferences("chart_favorites", 0)
        favorites.edit().putStringSet("identities", setOf("example")).commit()
        val token = context.getSharedPreferences("test_credentials", 0)
        token.edit().putString("token", "test-only").commit()
        val store = ComponentSettingsState(context)
        store.reset()
        val changed = ComponentSettings().replaceSlot(0, ThumbnailField.PlayCount).copy(hiddenDetails = setOf("PC", "曲师", "曲名"))
        store.save(changed)
        assertEquals(changed, ComponentSettingsState(context).value)
        store.reset()
        assertEquals(ComponentSettings(), ComponentSettingsState(context).value)
        assertEquals(setOf("example"), favorites.getStringSet("identities", emptySet()))
        assertEquals("test-only", token.getString("token", null))
    }
}
