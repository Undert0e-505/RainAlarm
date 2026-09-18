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

    /** A failed or stalled style keeps the themed cover, but makes its error visible. */
    fun markFailed(): Boolean {
        if (revealed || failed) return false
        failed = true
        return true
    }
}
