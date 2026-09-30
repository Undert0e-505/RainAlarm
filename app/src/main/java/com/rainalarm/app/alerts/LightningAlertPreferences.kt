package com.rainalarm.app.alerts

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Independent, explicit opt-in alert preference. Missing values intentionally decode to off. */
class LightningAlertPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val snapshots: Flow<AlertSnapshot> = callbackFlow {
        fun emitSnapshot() { trySend(snapshot()) }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            emitSnapshot()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        emitSnapshot()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun snapshot(): AlertSnapshot = AlertSnapshot(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        lastCheckedEpochSeconds = preferences.getLong(KEY_LAST_CHECKED, 0L),
        status = preferences.getString(
            KEY_STATUS,
            context.getString(R.string.notification_status_off),
        ) ?: context.getString(R.string.notification_status_off),
    )

    fun setEnabled(enabled: Boolean) {
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, enabled)
            putString(
                KEY_STATUS,
                context.getString(
                    if (enabled) R.string.notification_status_scheduled
                    else R.string.notification_status_off,
                ),
            )
        }
        // A newly enabled subscription is eligible for the first valid nearby detection. Reset
        // its alert checkpoint so an already-advertised latest frame can be evaluated immediately.
        if (enabled) MonitoringStateStore(context).resetLightningEligibility()
    }

    fun markPermissionNeeded() {
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLED, false)
            putString(KEY_STATUS, context.getString(R.string.notification_permission_needed))
        }
    }

    fun recordCheck(epochSeconds: Long, status: String) {
        preferences.edit(commit = true) {
            putLong(KEY_LAST_CHECKED, epochSeconds)
            putString(KEY_STATUS, status)
        }
    }

    fun relocalizeStatus(notificationPermissionGranted: Boolean) {
        val enabled = snapshot().enabled
        preferences.edit(commit = true) {
            putString(
                KEY_STATUS,
                context.getString(
                    when {
                        enabled -> R.string.notification_status_scheduled
                        !notificationPermissionGranted -> R.string.notification_permission_needed
                        else -> R.string.notification_status_off
                    },
                ),
            )
        }
    }

    private companion object {
        const val PREFS = "lightning_alerts"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_CHECKED = "last_checked"
        const val KEY_STATUS = "status"
    }
}
