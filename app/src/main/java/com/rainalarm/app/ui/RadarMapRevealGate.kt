package com.rainalarm.app.ui

/** Do not reveal a MapView until the current style has rendered a complete frame. */
internal class RadarMapRevealGate {
    private var generation = 0L
    private var loadedGeneration: Long? = null
    private var frameStartedAfterStyle = false
    private var revealed = false
    private var failed = false

    val isCovered: Boolean get() = !revealed
    val hasFailed: Boolean get() = failed

    fun styleRequested(): Long {
        generation++
        loadedGeneration = null
        frameStartedAfterStyle = false
        revealed = false
        failed = false
        return generation
    }

    /** A late callback from a superseded style must not open the current cover. */
    fun styleLoaded(requestGeneration: Long): Boolean {
        if (requestGeneration != generation) return false
        loadedGeneration = generation
        return true
    }

    fun frameStarted() {
        if (loadedGeneration == generation && !revealed) frameStartedAfterStyle = true
    }

    /** Partial/default and pre-style frames never reveal the map. */
    fun frameRendered(fully: Boolean): Boolean {
        val startedAfterCurrentStyle = frameStartedAfterStyle
        frameStartedAfterStyle = false // A partial finish cannot qualify a later unmatched full finish.
        if (!fully || !startedAfterCurrentStyle || loadedGeneration != generation || revealed) return false
        revealed = true
        failed = false
        return true
    }

    /**
     * MapLibre may omit the matching frame-start callback for an already cached style. Its
     * full-map callback is still authoritative once the current style generation loaded.
     */
    fun mapRendered(fully: Boolean): Boolean {
        if (!fully || loadedGeneration != generation || revealed) return false
        revealed = true
        failed = false
        frameStartedAfterStyle = false
        return true
    }

    /** A failed or stalled style keeps the themed cover, but makes its error visible. */
    fun markFailed(): Boolean {
        if (revealed || failed) return false
        failed = true
        return true
    }
}

/** Fast, bounded recovery for a native map that lost its render-completion edge. */
internal object RadarMapRecoveryPolicy {
    const val REPAINT_AFTER_MILLIS = 1_500L
    const val RECREATE_AFTER_MILLIS = 4_000L
    const val MAX_AUTOMATIC_RECREATIONS = 1

    fun shouldRecreate(isCovered: Boolean, automaticRecreations: Int): Boolean =
        isCovered && automaticRecreations < MAX_AUTOMATIC_RECREATIONS
}
