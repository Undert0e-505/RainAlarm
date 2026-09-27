package com.rainalarm.app.data

import android.annotation.SuppressLint
import android.content.Context

data class AppLanguageOption(
    val tag: String?,
    val nativeName: String,
)

object AppLanguagePolicy {
    val options: List<AppLanguageOption> = listOf(
        AppLanguageOption(null, "Device language"),
        AppLanguageOption("en", "English"),
        AppLanguageOption("nl", "Nederlands"),
        AppLanguageOption("nl-BE", "Nederlands (België)"),
        AppLanguageOption("de", "Deutsch"),
        AppLanguageOption("fr", "Français"),
        AppLanguageOption("cy", "Cymraeg"),
        AppLanguageOption("ga", "Gaeilge"),
    )

    fun normalizedTag(languageTags: String?): String? {
        val first = languageTags?.split(',')?.firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return null
        return options.firstOrNull { it.tag.equals(first, ignoreCase = true) }?.tag
    }

    fun option(tag: String?): AppLanguageOption =
        options.first { it.tag == normalizedTag(tag) }
}

internal object LanguageChoiceGatePolicy {
    fun initialCompletion(freshInstall: Boolean, storedCompletion: Boolean?): Boolean =
        storedCompletion ?: !freshInstall

    fun showChoice(startupReady: Boolean, completed: Boolean?): Boolean =
        startupReady && completed == false
}

/** Tracks only whether the one-time choice was made; AndroidX owns the locale override itself. */
@SuppressLint("ApplySharedPref")
class LanguageChoicePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("language_choice", Context.MODE_PRIVATE)

    fun initialize(freshInstall: Boolean): Boolean {
        val stored = if (preferences.contains(COMPLETED)) {
            preferences.getBoolean(COMPLETED, false)
        } else null
        val completed = LanguageChoiceGatePolicy.initialCompletion(freshInstall, stored)
        // Persist both outcomes. A fresh first run that is killed before confirmation must
        // still show the choice next launch even though the Places store is now migrated.
        if (stored == null) preferences.edit().putBoolean(COMPLETED, completed).commit()
        return completed
    }

    fun completed(): Boolean = preferences.getBoolean(COMPLETED, false)

    fun complete(): Boolean = preferences.edit().putBoolean(COMPLETED, true).commit()

    private companion object {
        const val COMPLETED = "completed"
    }
}
