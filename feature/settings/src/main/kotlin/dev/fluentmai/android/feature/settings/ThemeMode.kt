package dev.fluentmai.android.feature.settings

enum class ThemeMode(val label: String) {
    LIGHT("亮色模式"),
    SYSTEM("跟随系统"),
    DARK("深色模式");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        SYSTEM -> systemDark
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
