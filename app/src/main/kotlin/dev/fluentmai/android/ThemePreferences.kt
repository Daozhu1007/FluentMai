package dev.fluentmai.android

import android.content.Context
import dev.fluentmai.android.feature.settings.ThemeMode

internal class ThemePreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("fluentmai_appearance", Context.MODE_PRIVATE)

    fun reset() {
        preferences.edit().clear().apply()
        applicationContext.getSharedPreferences("b50_poster", Context.MODE_PRIVATE).edit().clear().apply()
    }

    var mode: ThemeMode
        get() = ThemeMode.fromStored(preferences.getString("theme_mode", null))
        set(value) { preferences.edit().putString("theme_mode", value.name).apply() }

    var automaticUpdates: Boolean
        get() = preferences.getBoolean("automatic_updates", true)
        set(value) { preferences.edit().putBoolean("automatic_updates", value).apply() }

    var efficientPc: Boolean
        get() = preferences.getBoolean("efficient_pc", true)
        set(value) { preferences.edit().putBoolean("efficient_pc", value).apply() }

    var experimentalFeatures: Boolean
        get() = preferences.getBoolean("experimental_features", false)
        set(value) { preferences.edit().putBoolean("experimental_features", value).apply() }
}
