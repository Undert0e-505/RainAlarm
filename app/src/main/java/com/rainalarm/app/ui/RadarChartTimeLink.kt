package com.rainalarm.app.ui

/** Sticky navigation intent; the selected-place ID remains stable across session promotion/refresh. */
data class RadarChartTimeRequest(
    val token: Int,
    val selectedPlaceId: String,
    val epochSeconds: Double,
)

internal sealed interface RadarChartTimeDecision {
    data object Waiting : RadarChartTimeDecision
    data class Apply(val cursorSeconds: Float) : RadarChartTimeDecision
    data object Unavailable : RadarChartTimeDecision
}

internal object RadarChartTimeLink {
    fun shouldDiscard(request: RadarChartTimeRequest?, selectedPlaceId: String, leavingRadar: Boolean): Boolean =
        request != null && (leavingRadar || request.selectedPlaceId != selectedPlaceId)

    fun decide(
        request: RadarChartTimeRequest,
        selectedPlaceId: String,
        loadedPlaceId: String?,
        firstEpochSeconds: Long?,
        endEpochSeconds: Double?,
    ): RadarChartTimeDecision {
        if (request.selectedPlaceId != selectedPlaceId) return RadarChartTimeDecision.Unavailable
        if (loadedPlaceId == null || firstEpochSeconds == null || endEpochSeconds == null)
            return RadarChartTimeDecision.Waiting
        // A retained old session while the requested place loads is not evidence that the intent
        // is unavailable. Wait for its replacement instead of consuming the request.
        if (loadedPlaceId != selectedPlaceId) return RadarChartTimeDecision.Waiting
        if (!request.epochSeconds.isFinite() || !endEpochSeconds.isFinite() ||
            endEpochSeconds < firstEpochSeconds) return RadarChartTimeDecision.Unavailable
        // Publication refresh can move the real domain past the requested instant. Preserve the
        // absolute intent and deterministically select the nearest available edge, still paused.
        val resolvedEpoch = request.epochSeconds.coerceIn(
            firstEpochSeconds.toDouble(), endEpochSeconds,
        )
        return RadarChartTimeDecision.Apply((resolvedEpoch - firstEpochSeconds).toFloat())
    }
}
