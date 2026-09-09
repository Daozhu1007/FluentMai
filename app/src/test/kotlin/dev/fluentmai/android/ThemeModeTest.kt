package dev.fluentmai.android

import dev.fluentmai.android.feature.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun missingOrUnknownPreferenceDefaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("unknown"))
    }

    @Test
    fun savedModesRoundTrip() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.name)) }
    }

    @Test
    fun explicitChoicesOverrideSystemWhileSystemChoiceTracksIt() {
        for (systemDark in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
        }
    }
}
