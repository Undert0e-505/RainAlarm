package com.rainalarm.app.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

/** Late-bound in one place so weather coordination does not depend on Glance rendering details. */
object WidgetUpdatePublisher {
    internal val refreshToken = longPreferencesKey("weather_refresh_token")

    suspend fun updateAll(context: Context) {
        try {
            val widget = RainAlarmWidget()
            GlanceAppWidgetManager(context).getGlanceIds(RainAlarmWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { preferences ->
                    preferences[refreshToken] = System.nanoTime()
                }
                widget.update(context, id)
                runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }
                    .getOrNull()
                    ?.let(WidgetRefreshTrace::glanceInvalidated)
            }
        } finally {
            // Re-evaluate local presentation demand after every data/config repaint. This never
            // performs acquisition or alert work; it only maintains the one minute-boundary tick.
            WidgetPresentationTicker.reconcile(context)
        }
    }
}
