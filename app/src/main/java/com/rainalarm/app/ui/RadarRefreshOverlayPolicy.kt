package com.rainalarm.app.ui

internal data class RadarRefreshOverlay(val label: String, val accessibilityLabel: String)

/** A reusable session keeps its map geometry while refresh feedback floats above it. */
internal object RadarRefreshOverlayPolicy {
    fun initialLoading(
        completed: Int,
        total: Int,
        preparing: Boolean = false,
    ): RadarRefreshOverlay {
        val transferComplete = total > 0 && completed >= total
        val label = if (preparing || transferComplete) {
            WeatherDataStatusPolicy.preparing(WeatherDataKind.RADAR)
        }
        else WeatherDataStatusPolicy.loading(
            WeatherDataKind.RADAR, completed.takeIf { total > 0 }, total.takeIf { total > 0 },
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
        preparing: Boolean = false,
    ): RadarRefreshOverlay? {
        if (!hasSession) return error?.takeIf(String::isNotBlank)?.let {
            val label = WeatherDataStatusPolicy.unavailable(WeatherDataKind.RADAR)
            RadarRefreshOverlay(label, label)
        }
        if (refreshing) {
            val transferComplete = total > 0 && completed >= total
            val label = if (preparing || transferComplete) {
                WeatherDataStatusPolicy.preparing(WeatherDataKind.RADAR)
            }
            else WeatherDataStatusPolicy.loading(
                WeatherDataKind.RADAR,
                completed.takeIf { total > 0 },
                total.takeIf { total > 0 },
            )
            return RadarRefreshOverlay(label, WeatherDataStatusPolicy.loading(WeatherDataKind.RADAR))
        }
        // A failed replacement leaves the already-renderable session on screen. Provider errors
        // remain in diagnostics and the next manual/automatic refresh retries them; they must not
        // demote the valid session to "unavailable".
        return null
    }
}
