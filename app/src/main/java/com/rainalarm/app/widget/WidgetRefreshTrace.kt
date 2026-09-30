package com.rainalarm.app.widget

import android.util.Log
import java.security.MessageDigest

/** Coordinate-free debug evidence for configuration -> work -> state -> Glance hand-off. */
object WidgetRefreshTrace {
    private const val TAG = "RainWidgetRefresh"

    fun configSaved(config: RainAlarmWidgetConfig, pending: Boolean) = log(
        "config_saved widget=${config.appWidgetId} targetType=saved pending=$pending",
    )

    fun migration(migrated: Int, required: Int) = log(
        "migration migrated=$migrated configurationRequired=$required",
    )

    fun subscriptionRegistered(widgetId: Int, targetKey: String, pending: Boolean) = log(
        "subscription_registered widget=$widgetId target=${targetHash(targetKey)} pending=$pending",
    )

    fun workEnqueued(pendingCount: Int) = log("work_enqueued pending=$pendingCount")

    fun workerStarted(pendingCount: Int, attempt: Int) = log(
        "worker_start pending=$pendingCount attempt=$attempt",
    )

    fun workerDeferred(pendingCount: Int) = log("worker_foreground_deferred pending=$pendingCount")

    fun workerFinished(result: String, pendingCount: Int) = log(
        "worker_result result=$result pending=$pendingCount",
    )

    fun targetStarted(targetKey: String, widgetIds: Collection<Int>) = log(
        "target_start target=${targetHash(targetKey)} widgets=${widgetIds.sorted().joinToString(",")}",
    )

    fun streamFinished(targetKey: String, stream: String, result: String, elapsedMillis: Long) = log(
        "stream_result target=${targetHash(targetKey)} stream=$stream result=$result " +
            "elapsedMs=${elapsedMillis.coerceAtLeast(0L)}",
    )

    fun stateWritten(
        targetKey: String,
        widgetIds: Collection<Int>,
        usable: Boolean,
        outcome: String,
    ) = log(
        "state_written target=${targetHash(targetKey)} widgets=${widgetIds.sorted().joinToString(",")}" +
            " usable=$usable outcome=$outcome",
    )

    fun glanceInvalidated(widgetId: Int) = log("glance_invalidated widget=$widgetId")

    fun deleted(widgetIds: Collection<Int>) = log(
        "subscriptions_deleted widgets=${widgetIds.sorted().joinToString(",")}",
    )

    fun targetHash(targetKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(targetKey.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    // Deliberately coordinate-free and compact so signed preview/device diagnosis remains possible.
    private fun log(message: String) = Log.i(TAG, message)
}
