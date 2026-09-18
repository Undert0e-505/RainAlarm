package com.rainalarm.app.ui

/** Do not reveal a newly attached MapView until its requested style has rendered a frame. */
internal class RadarMapRevealGate {
    private var styleLoaded = false
    private var revealed = false
    private var failed = false

    val isCovered: Boolean get() = !revealed
    val hasFailed: Boolean get() = failed

    fun styleRequested() {
        styleLoaded = false
        revealed = false
        failed = false
    }

    fun styleLoaded() {
        styleLoaded = true
    }

    /** Returns true exactly once, after the current style has produced a map frame. */
    fun frameRendered(): Boolean {
        if (!styleLoaded || revealed) return false
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
