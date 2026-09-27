package com.rainalarm.app.data

import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainIntensity
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainStatus
import com.rainalarm.app.domain.RainSummary
import java.time.Instant

data class FeatureTourRadarFrame(
    val epochSeconds: Long,
    /** Deterministic southwest-to-northeast presentation progress. */
    val motionProgress: Float,
)

data class FeatureTourScenario(
    val forecast: ForecastSnapshot,
    val radarFrames: List<FeatureTourRadarFrame>,
    val fallbackPlace: SavedPlace,
) {
    init {
        require(radarFrames.size >= 4)
        require(radarFrames.zipWithNext().all { (a, b) -> b.epochSeconds > a.epochSeconds })
    }

    fun radarMotionProgress(epochSeconds: Double): Float {
        val first = radarFrames.first()
        val last = radarFrames.last()
        if (last.epochSeconds == first.epochSeconds) return first.motionProgress
        val fraction = ((epochSeconds - first.epochSeconds) /
            (last.epochSeconds - first.epochSeconds).toDouble()).coerceIn(0.0, 1.0)
        return (first.motionProgress + (last.motionProgress - first.motionProgress) * fraction).toFloat()
    }

    companion object {
        const val RAIN_ARRIVAL_MINUTE = 11
        const val RADAR_FRAME_COUNT = 7
        const val SOURCE_BEARING_DEGREES = 225.0
        private const val FRAME_INTERVAL_SECONDS = 5 * 60L

        fun create(
            nowEpochSeconds: Long,
            selectedPlace: SavedPlace?,
            exampleLabel: String,
            summaryHeadline: String,
            summaryDetail: String,
        ): FeatureTourScenario {
            val minuteAnchor = Math.floorDiv(nowEpochSeconds, 60L) * 60L
            val quarterAnchor = Math.floorDiv(nowEpochSeconds, 15 * 60L) * 15 * 60L
            val place = selectedPlace ?: DEFAULT_PLACE.copy(id = "feature-tour-fallback")
            val series = RainMinuteSeries(
                startEpochSeconds = minuteAnchor,
                points = (0..60).map { minute ->
                    val average = intensityAt(minute)
                    RainMinutePoint(
                        minute = minute,
                        minimum = (average - 0.07f).coerceAtLeast(0f),
                        average = average,
                        maximum = (average + 0.10f).coerceAtMost(1f),
                        forecast = minute > 0,
                    )
                },
                sourceLabel = exampleLabel,
                confidence = 1.0,
                availability = RainMinuteAvailability.AVAILABLE,
                latestObservationEpochSeconds = minuteAnchor,
                travelBearingDegrees = 45.0,
                sourceBearingDegrees = SOURCE_BEARING_DEGREES,
                intensityEncoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE,
            )
            val forecast = ForecastSnapshot(
                locationName = place.name,
                fetchedAt = Instant.ofEpochSecond(minuteAnchor),
                slots = emptyList(),
                summary = RainSummary(
                    status = RainStatus.APPROACHING,
                    headline = summaryHeadline,
                    detail = summaryDetail,
                    arrivalMinutes = RAIN_ARRIVAL_MINUTE,
                    endMinutes = 49,
                    peakRateMmPerHour = 3.2,
                    peakProbabilityPercent = 82,
                    intensity = RainIntensity.MODERATE,
                ),
                isDemo = true,
                sourceLabel = exampleLabel,
                isRadarNowcast = true,
                nowcastSeries = series,
            )
            val frames = (0 until RADAR_FRAME_COUNT).map { index ->
                FeatureTourRadarFrame(
                    epochSeconds = quarterAnchor + index * FRAME_INTERVAL_SECONDS,
                    motionProgress = index.toFloat() / (RADAR_FRAME_COUNT - 1),
                )
            }
            return FeatureTourScenario(forecast, frames, place)
        }

        private fun intensityAt(minute: Int): Float = when (minute) {
            in 0..8 -> 0.04f
            9 -> 0.10f
            10 -> 0.18f
            11 -> 0.30f
            in 12..18 -> 0.30f + (minute - 11) * 0.045f
            in 19..26 -> 0.64f
            in 27..40 -> 0.62f - (minute - 27) * 0.018f
            in 41..48 -> 0.37f - (minute - 41) * 0.018f
            else -> 0.07f
        }.coerceIn(0f, 1f)
    }
}
