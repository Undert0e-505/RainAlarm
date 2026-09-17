package com.rainalarm.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal sealed interface PrimaryRadarAttempt<out T> {
    data class Ready<T>(val value: T) : PrimaryRadarAttempt<T>
    data object TimedOut : PrimaryRadarAttempt<Nothing>
    data class Failed(val cause: Throwable) : PrimaryRadarAttempt<Nothing>
}

/** Wall-clock budgets for a complete radar session, including any open-provider fallback. */
internal object RadarLoadDeadline {
    const val SCREEN_PRIMARY_MILLIS = 60_000L
    const val SCREEN_TOTAL_MILLIS = 90_000L
    const val ANALYSIS_PRIMARY_MILLIS = 40_000L
    const val ANALYSIS_TOTAL_MILLIS = 60_000L
    const val NOW_TOTAL_MILLIS = 75_000L

    fun primary(mode: RadarLoadMode): Long = when (mode) {
        RadarLoadMode.SCREEN_TWO_TIER -> SCREEN_PRIMARY_MILLIS
        RadarLoadMode.ALERT_ANALYSIS -> ANALYSIS_PRIMARY_MILLIS
    }

    fun total(mode: RadarLoadMode): Long = when (mode) {
        RadarLoadMode.SCREEN_TWO_TIER -> SCREEN_TOTAL_MILLIS
        RadarLoadMode.ALERT_ANALYSIS -> ANALYSIS_TOTAL_MILLIS
    }

    fun remaining(totalMillis: Long, startNanos: Long, nowNanos: Long): Long =
        (totalMillis - ((nowNanos - startNanos).coerceAtLeast(0L) / 1_000_000L)).coerceAtLeast(0L)

    /** Only our own deadline permits fallback; parent/user cancellation must propagate. */
    suspend fun <T> attemptPrimary(timeoutMillis: Long, load: suspend () -> T): PrimaryRadarAttempt<T> =
        try {
            PrimaryRadarAttempt.Ready(withTimeout(timeoutMillis) { load() })
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            PrimaryRadarAttempt.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            PrimaryRadarAttempt.Failed(failure)
        }
}

class RadarLoadTimedOutException(message: String) : IOException(message)
