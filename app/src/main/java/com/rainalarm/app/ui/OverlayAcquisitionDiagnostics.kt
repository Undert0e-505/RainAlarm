package com.rainalarm.app.ui

import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest

/**
 * Small publication gate shared by the Radar-screen acquisition jobs.
 *
 * Compose cancels a replaced [androidx.compose.runtime.LaunchedEffect], but provider and bitmap
 * work can be inside a non-suspending platform call when cancellation arrives. The explicit
 * generation therefore remains the final authority for every progress and terminal publication.
 */
internal class OverlayRequestGeneration {
    private var activeGeneration = 0L

    fun begin(): Long = if (activeGeneration == Long.MAX_VALUE) {
        1L.also { activeGeneration = it }
    } else {
        (++activeGeneration)
    }

    fun accepts(generation: Long): Boolean = generation == activeGeneration

    val active: Long get() = activeGeneration
}

internal object OverlayPublicationPolicy {
    fun accepts(activeGeneration: Long, candidateGeneration: Long): Boolean =
        activeGeneration == candidateGeneration

    /** A failed replacement cannot demote a usable, already published session. */
    fun showUnavailable(hasUsableLastGood: Boolean): Boolean = !hasUsableLastGood
}

internal enum class OverlayAcquisitionPhase {
    REQUEST,
    TRANSFER,
    PREPARING,
    RENDERING,
    READY,
    FAILED,
    CANCELLED,
}

/** Coordinate-free debug evidence for slow/racing weather acquisition. */
internal object OverlayAcquisitionDiagnostics {
    private const val TAG = "RainOverlayLoad"

    fun targetHash(vararg identityParts: Any?): String {
        val value = identityParts.joinToString("|") { it?.toString().orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun nowMillis(): Long = SystemClock.elapsedRealtime()

    fun trace(
        layer: String,
        provider: String,
        targetHash: String,
        generation: Long,
        trigger: String,
        phase: OverlayAcquisitionPhase,
        startedAtMillis: Long,
        completed: Int? = null,
        total: Int? = null,
        terminalResult: String? = null,
        cacheFallback: Boolean = false,
        accepted: Boolean = true,
    ) {
        // Signed previews retain sparse, coordinate-free phase evidence. Intermediate transfer
        // counts are sampled to avoid one log line per raster resource.
        if (phase == OverlayAcquisitionPhase.TRANSFER && completed != null && total != null &&
            completed !in setOf(0, total) && completed % 10 != 0
        ) return
        val progress = if (completed != null && total != null) " frames=$completed/$total" else ""
        val terminal = terminalResult?.let { " result=$it" }.orEmpty()
        Log.i(
            TAG,
            "layer=$layer provider=$provider target=$targetHash generation=$generation " +
                "trigger=$trigger phase=${phase.name.lowercase()} elapsedMs=" +
                "${(nowMillis() - startedAtMillis).coerceAtLeast(0L)}$progress$terminal " +
                "cacheFallback=$cacheFallback accepted=$accepted",
        )
    }
}
