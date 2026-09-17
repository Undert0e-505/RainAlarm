package com.rainalarm.app.data

/** An independent card surface preference. FOLLOW_APP preserves existing behaviour. */
enum class NowCardAppearance(val label: String) {
    FOLLOW_APP("Follow app"), LIGHT("Light"), DARK("Dark");

    fun isDark(appIsDark: Boolean): Boolean = when (this) {
        FOLLOW_APP -> appIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun decode(value: String?): NowCardAppearance = entries.firstOrNull { it.name == value } ?: FOLLOW_APP
    }
}
