package com.rainalarm.app.domain

/** Ensures overlay/map resources relinquish session bitmaps before they are recycled. */
class RadarResourceTeardown(
    private val stopOverlay: () -> Unit,
    private val detachMap: () -> Unit,
    private val destroyMap: () -> Unit,
    private val releaseSession: () -> Unit,
) {
    private var closed = false

    val isClosed: Boolean get() = closed

    fun close() {
        if (closed) return
        closed = true
        stopOverlay()
        try {
            detachMap()
        } finally {
            try {
                destroyMap()
            } finally {
                releaseSession()
            }
        }
    }
}
