package com.rainalarm.app.ui

internal data class RadarRefreshOverlay(val label: String, val accessibilityLabel: String)

/** A reusable session keeps its map geometry while refresh feedback floats above it. */
internal object RadarRefreshOverlayPolicy {
    fun initialLoading(completed: Int, total: Int): RadarRefreshOverlay {
        val label = WeatherDataStatusPolicy.loading(
            WeatherDataKind.RADAR,
            completed.takeIf { total > 0 },
            total.takeIf { total > 0 },
        )
        // Keep the semantic label stable while visible progress changes so assistive technology
        // can identify the state without announcing every downloaded resource.
        return RadarRefreshOverlay(label, WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR))
    }

    fun status(
        hasSession: Boolean,
        refreshing: Boolean,
        completed: Int,
        total: Int,
        error: String?,
    ): RadarRefreshOverlay? {
        if (!hasSession) return error?.takeIf(String::isNotBlank)?.let {
            val label = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)
            RadarRefreshOverlay(label, label)
        }
        if (refreshing) {
            val label = WeatherDataStatusPolicy.loading(
                WeatherDataKind.RADAR,
                completed.takeIf { total > 0 },
                total.takeIf { total > 0 },
            )
            return RadarRefreshOverlay(label, WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR))
        }
        if (error.isNullOrBlank()) return null
        val label = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)
        return RadarRefreshOverlay(label, label)
    }
}
