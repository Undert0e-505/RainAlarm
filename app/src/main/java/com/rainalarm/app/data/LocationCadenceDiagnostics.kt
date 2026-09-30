package com.rainalarm.app.data

import android.util.Log
import com.rainalarm.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, coordinate-free evidence for the Travel fix pipeline. */
object LocationCadenceDiagnostics {
    private const val TAG = "RainAlarmTravelCadence"
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val lastTimestamps = ConcurrentHashMap<String, AtomicLong>()
    private val travelEnabled = AtomicBoolean(false)

    private fun loggingEnabled(): Boolean =
        BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)

    fun setTravelEnabled(enabled: Boolean) {
        val changed = travelEnabled.getAndSet(enabled) != enabled
        if (changed && enabled) {
            counts.clear()
            lastTimestamps.clear()
            if (loggingEnabled()) Log.d(
                TAG,
                "stage=lifecycle source=none status=enabled elapsedNanos=0 count=0 deltaMs=none",
            )
        } else if (changed && loggingEnabled()) {
            Log.d(TAG, "stage=lifecycle source=none status=disabled elapsedNanos=0 count=0 deltaMs=none")
        }
    }

    fun record(stage: String, source: LocationFixSource?, elapsedRealtimeNanos: Long, status: String) {
        if (!travelEnabled.get() || !loggingEnabled()) return
        val count = counts.getOrPut(stage) { AtomicLong() }.incrementAndGet()
        val previous = lastTimestamps.getOrPut(stage) { AtomicLong() }.getAndSet(elapsedRealtimeNanos)
        val delta = if (previous > 0L && elapsedRealtimeNanos >= previous) {
            ((elapsedRealtimeNanos - previous) / 1_000_000L).toString()
        } else "none"
        Log.d(
            TAG,
            "stage=$stage source=${source?.name?.lowercase() ?: "none"} status=$status " +
                "elapsedNanos=$elapsedRealtimeNanos count=$count deltaMs=$delta",
        )
    }
}

/** Android-free counter used to measure whether a delivered stream is coalesced by app policy. */
class LocationCadenceAccumulator {
    private val timestamps = mutableListOf<Long>()

    fun record(elapsedRealtimeNanos: Long) {
        if (timestamps.lastOrNull()?.let { elapsedRealtimeNanos <= it } == true) return
        timestamps += elapsedRealtimeNanos
    }

    fun countBetween(startNanos: Long, endNanos: Long): Int =
        timestamps.count { it in startNanos until endNanos }
}
