package dev.fluentmai.android

import android.content.Context
import dev.fluentmai.android.feature.settings.ThemeMode

internal class ThemePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("fluentmai_appearance", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = ThemeMode.fromStored(preferences.getString("theme_mode", null))
        set(value) { preferences.edit().putString("theme_mode", value.name).apply() }
}
