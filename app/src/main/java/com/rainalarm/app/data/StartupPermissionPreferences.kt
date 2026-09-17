package com.rainalarm.app.data

import android.annotation.SuppressLint
import android.content.Context

/** One-shot foreground permission prompts. Consume before launching the OS dialog. */
@SuppressLint("ApplySharedPref", "UseKtx") // Durable markers must commit before an OS dialog; KTX edit has no result.
class StartupPermissionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("startup_permissions", Context.MODE_PRIVATE)

    fun initializeFreshInstall(queueNotification: Boolean) {
        val edit = preferences.edit()
        if (!preferences.contains("location_attempted")) edit.putBoolean("location_pending", true)
        if (queueNotification && !preferences.contains("notification_attempted"))
            edit.putBoolean("notification_pending", true)
        edit.commit()
    }

    fun queueNotificationIfUnanswered() {
        if (preferences.getBoolean("notification_attempted", false)) return
        preferences.edit().putBoolean("notification_pending", true).commit()
    }

    fun locationPending(): Boolean = preferences.getBoolean("location_pending", false)
    fun notificationPending(): Boolean = preferences.getBoolean("notification_pending", false)
    fun notificationAttempted(): Boolean = preferences.getBoolean("notification_attempted", false)

    fun consumeLocation(): Boolean = consume("location")
    fun consumeNotification(): Boolean = consume("notification")

    private fun consume(kind: String): Boolean {
        if (!preferences.getBoolean("${kind}_pending", false)) return false
        return preferences.edit().putBoolean("${kind}_pending", false)
            .putBoolean("${kind}_attempted", true).commit()
    }
}

internal enum class StartupPermissionStep { LOCATION, NOTIFICATION, NONE }

internal object StartupPermissionPolicy {
    fun next(locationPending: Boolean, notificationPending: Boolean,
             locationGranted: Boolean, notificationGranted: Boolean): StartupPermissionStep = when {
        locationPending && !locationGranted -> StartupPermissionStep.LOCATION
        notificationPending && !notificationGranted -> StartupPermissionStep.NOTIFICATION
        else -> StartupPermissionStep.NONE
    }
}
