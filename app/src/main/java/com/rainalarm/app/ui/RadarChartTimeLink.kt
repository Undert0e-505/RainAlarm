package com.rainalarm.app.ui

/** Ephemeral navigation intent; the selected-place ID remains stable across small live-fix drift. */
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
        if (loadedPlaceId != selectedPlaceId || !request.epochSeconds.isFinite() ||
            !endEpochSeconds.isFinite() || request.epochSeconds < firstEpochSeconds ||
            request.epochSeconds > endEpochSeconds) return RadarChartTimeDecision.Unavailable
        return RadarChartTimeDecision.Apply((request.epochSeconds - firstEpochSeconds).toFloat())
    }
}
