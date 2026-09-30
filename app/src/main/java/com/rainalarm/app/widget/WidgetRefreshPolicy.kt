package com.rainalarm.app.widget

/**
 * Pure policy for a widget's durable first acquisition.
 *
 * A pending registration survives Activity/process lifecycle races. Widget acquisition is
 * independent of Activity visibility; only completed attempts which yield no usable stream advance
 * the bounded failure count.
 */
object WidgetInitialRefreshPolicy {
    const val maxCompletedFailures = 3

    enum class WorkerDirective { RUN, FINISH }
    enum class AttemptDisposition { COMPLETE, RETRY, TERMINAL_FAILURE }

    fun workerDirective(hasPendingRefresh: Boolean, appVisible: Boolean): WorkerDirective =
        if (hasPendingRefresh) WorkerDirective.RUN else WorkerDirective.FINISH

    fun attemptDisposition(
        usableSnapshotProduced: Boolean,
        completedFailureCount: Int,
    ): AttemptDisposition = when {
        usableSnapshotProduced -> AttemptDisposition.COMPLETE
        completedFailureCount < maxCompletedFailures -> AttemptDisposition.RETRY
        else -> AttemptDisposition.TERMINAL_FAILURE
    }

    /** Identical targets share one acquisition without losing any subscribing widget id. */
    fun coalesce(registrations: Collection<WidgetRefreshRegistration>): Map<String, Set<Int>> =
        registrations.groupBy(WidgetRefreshRegistration::targetKey)
            .mapValues { (_, values) -> values.mapTo(linkedSetOf(), WidgetRefreshRegistration::widgetId) }
}

data class WidgetRefreshRegistration(
    val widgetId: Int,
    val targetKey: String,
)

data class WidgetInitialFailureResult(
    val retryIds: Set<Int>,
    val terminalIds: Set<Int>,
)
