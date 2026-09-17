package com.rainalarm.app.alerts

/** Null means the user has never chosen an alert setting; explicit false always wins. */
internal enum class AlertStartupAction { SCHEDULE, REQUEST_PERMISSION, PERMISSION_NEEDED, NONE }

internal object AlertStartupPolicy {
    fun decide(explicitEnabled: Boolean?, permissionGranted: Boolean,
               permissionPromptAttempted: Boolean): AlertStartupAction = when {
        explicitEnabled == false -> AlertStartupAction.NONE
        permissionGranted -> AlertStartupAction.SCHEDULE
        !permissionPromptAttempted -> AlertStartupAction.REQUEST_PERMISSION
        else -> AlertStartupAction.PERMISSION_NEEDED
    }
}
