package com.rainalarm.app.widget

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.NowCardAppearance

data class WidgetResolvedAppearance(
    val app: WidgetPaletteKind = WidgetPaletteKind.DARK,
    val compass: WidgetPaletteKind = WidgetPaletteKind.DARK,
    val graph: WidgetPaletteKind = WidgetPaletteKind.DARK,
)

class WidgetAppearanceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): WidgetResolvedAppearance = WidgetResolvedAppearance(
        app = decode(preferences.getString(KEY_APP, null), WidgetPaletteKind.DARK),
        compass = decode(preferences.getString(KEY_COMPASS, null), WidgetPaletteKind.DARK),
        graph = decode(preferences.getString(KEY_GRAPH, null), WidgetPaletteKind.DARK),
    )

    /** Returns true only when a widget/configuration repaint is needed. */
    fun update(
        app: AppearanceMode,
        compass: NowCardAppearance,
        graph: NowCardAppearance,
        systemDark: Boolean,
    ): Boolean {
        val appKind = when (app) {
            AppearanceMode.LIGHT -> WidgetPaletteKind.LIGHT
            AppearanceMode.SLATE -> WidgetPaletteKind.SLATE
            AppearanceMode.FOLLOW_SYSTEM -> if (systemDark) WidgetPaletteKind.DARK else WidgetPaletteKind.LIGHT
            AppearanceMode.DARK -> WidgetPaletteKind.DARK
        }
        fun card(value: NowCardAppearance): WidgetPaletteKind = when (value) {
            NowCardAppearance.FOLLOW_APP -> appKind
            NowCardAppearance.LIGHT -> WidgetPaletteKind.LIGHT
            NowCardAppearance.DARK -> WidgetPaletteKind.DARK
            NowCardAppearance.SLATE -> WidgetPaletteKind.SLATE
        }
        val resolved = WidgetResolvedAppearance(appKind, card(compass), card(graph))
        if (current() == resolved) return false
        preferences.edit(commit = true) {
            putString(KEY_APP, resolved.app.name)
            putString(KEY_COMPASS, resolved.compass.name)
            putString(KEY_GRAPH, resolved.graph.name)
        }
        return true
    }

    private fun decode(raw: String?, fallback: WidgetPaletteKind): WidgetPaletteKind =
        WidgetPaletteKind.entries.firstOrNull { it.name == raw } ?: fallback

    private companion object {
        const val PREFS = "widget_appearance"
        const val KEY_APP = "app"
        const val KEY_COMPASS = "compass"
        const val KEY_GRAPH = "graph"
    }
}
