package com.rainalarm.app.ui

/** Sticky navigation intent; the selected-place ID remains stable across session promotion/refresh. */
data class RadarChartTimeRequest(
    val token: Int,
    val selectedPlaceId: String,
    val epochSeconds: Double,
)

internal sealed interface RadarChartTimeDecision {
    data object Waiting : RadarChartTimeDecision
    data class Apply(
        val cursorSeconds: Float,
        /** Absolute graph intent retained across a changed session origin. */
        val selectedEpochSeconds: Double,
        val covered: Boolean,
    ) : RadarChartTimeDecision
    data object Unavailable : RadarChartTimeDecision
}

internal object RadarTimelineActivationPolicy {
    fun automaticOnActivation(
        following: Boolean,
        chartIntentPending: Boolean,
        explicitTravelRequest: Boolean,
    ): Boolean = following && (explicitTravelRequest || !chartIntentPending)
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
        // absolute intent and expose whether the nearest edge is only a UI position; callers must
        // not render that edge as though it represented the requested time.
        val covered = request.epochSeconds in firstEpochSeconds.toDouble()..endEpochSeconds
        val resolvedEpoch = request.epochSeconds.coerceIn(
            firstEpochSeconds.toDouble(), endEpochSeconds,
        )
        return RadarChartTimeDecision.Apply(
            cursorSeconds = (resolvedEpoch - firstEpochSeconds).toFloat(),
            selectedEpochSeconds = request.epochSeconds,
            covered = covered,
        )
    }
}
