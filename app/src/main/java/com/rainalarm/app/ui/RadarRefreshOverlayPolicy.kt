package com.rainalarm.app.ui

internal data class RadarRefreshOverlay(val label: String, val accessibilityLabel: String)

/** A reusable session keeps its map geometry while refresh feedback floats above it. */
internal object RadarRefreshOverlayPolicy {
    fun initialLoading(completed: Int, total: Int): RadarRefreshOverlay {
        val progress = if (total > 0) {
            " ${completed.coerceIn(0, total)}/$total"
        } else {
            "…"
        }
        // Keep the semantic label stable while visible progress changes so assistive technology
        // can identify the state without announcing every downloaded resource.
        return RadarRefreshOverlay("Loading radar$progress", "Loading radar")
    }

    fun status(
        hasSession: Boolean,
        refreshing: Boolean,
        completed: Int,
        total: Int,
        error: String?,
    ): RadarRefreshOverlay? {
        if (!hasSession) return null
        if (refreshing) {
            val progress = if (total > 0) " · ${completed.coerceIn(0, total)}/$total" else "…"
            val spoken = if (total > 0) "${completed.coerceIn(0, total)} of $total resources"
                else "in progress"
            return RadarRefreshOverlay("Refreshing radar$progress", "Refreshing radar, $spoken")
        }
        if (error.isNullOrBlank()) return null
        return RadarRefreshOverlay("Radar refresh failed · retry",
            "Radar refresh failed: $error. Tap Refresh radar and map layer to retry.")
    }
}
