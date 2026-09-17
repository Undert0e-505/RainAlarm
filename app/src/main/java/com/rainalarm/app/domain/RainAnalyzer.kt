package com.rainalarm.app.domain

import java.time.Duration
import java.time.Instant

const val WET_THRESHOLD_MM = 0.1
const val FORECAST_FRESH_MINUTES = 45L

data class PrecipitationSlot(
    val startsAt: Instant,
    val precipitationMm: Double,
    val probabilityPercent: Int? = null,
)

enum class RainStatus { DRY, APPROACHING, RAINING, STALE, UNAVAILABLE, UNSUPPORTED_RESOLUTION }

enum class RainIntensity(val label: String) {
    LIGHT("Light"), MODERATE("Moderate"), HEAVY("Heavy")
}

data class RainSummary(
    val status: RainStatus,
    val headline: String,
    val detail: String,
    val arrivalMinutes: Int? = null,
    val endMinutes: Int? = null,
    val peakRateMmPerHour: Double? = null,
    val peakProbabilityPercent: Int? = null,
    val intensity: RainIntensity? = null,
)

object RainAnalyzer {
    fun analyze(
        slots: List<PrecipitationSlot>?,
        now: Instant,
        fetchedAt: Instant,
        intervalMinutes: Int = 15,
    ): RainSummary {
        if (slots.isNullOrEmpty()) {
            return RainSummary(RainStatus.UNAVAILABLE, "Forecast unavailable", "No precipitation timeline was returned.")
        }
        if (intervalMinutes != 15) {
            return RainSummary(
                RainStatus.UNSUPPORTED_RESOLUTION,
                "Forecast resolution unsupported",
                "This source is not providing honest 15-minute precipitation buckets.",
            )
        }
        val ageMinutes = Duration.between(fetchedAt, now).toMinutes().coerceAtLeast(0)
        if (ageMinutes > FORECAST_FRESH_MINUTES) {
            return RainSummary(
                RainStatus.STALE,
                "Forecast is out of date",
                "Last successful update was ${ageMinutes} minutes ago.",
            )
        }

        val ordered = slots.sortedBy { it.startsAt }
        val currentIndex = ordered.indexOfLast { !it.startsAt.isAfter(now) }.coerceAtLeast(0)
        val relevant = ordered.drop(currentIndex)
        val currentWet = relevant.first().precipitationMm >= WET_THRESHOLD_MM
        val peak = relevant.maxOf { it.precipitationMm } * 4.0
        val peakProbability = relevant.mapNotNull { it.probabilityPercent }.maxOrNull()
        val intensity = intensityFor(peak)

        if (currentWet) {
            val dryIndex = relevant.indexOfFirst { it.precipitationMm < WET_THRESHOLD_MM }
            val end = dryIndex.takeIf { it >= 0 }?.times(intervalMinutes)
            return RainSummary(
                status = RainStatus.RAINING,
                headline = "${intensity.label} rain now",
                detail = end?.let { "Expected to ease in about ${it} min" }
                    ?: "Continuing through the two-hour window",
                endMinutes = end,
                peakRateMmPerHour = peak,
                peakProbabilityPercent = peakProbability,
                intensity = intensity,
            )
        }

        val arrivalIndex = relevant.indexOfFirst { it.precipitationMm >= WET_THRESHOLD_MM }
        if (arrivalIndex < 0) {
            return RainSummary(
                RainStatus.DRY,
                "Dry for the next 2 hours",
                peakProbability?.let { "Highest rain chance is ${it}%" }
                    ?: "No measurable precipitation in the forecast.",
                peakRateMmPerHour = 0.0,
                peakProbabilityPercent = peakProbability,
            )
        }
        val afterArrival = relevant.drop(arrivalIndex)
        val dryAfter = afterArrival.indexOfFirst { it.precipitationMm < WET_THRESHOLD_MM }
        val arrival = arrivalIndex * intervalMinutes
        val end = dryAfter.takeIf { it >= 0 }?.let { (arrivalIndex + it) * intervalMinutes }
        return RainSummary(
            status = RainStatus.APPROACHING,
            headline = "Rain in ${arrival} min",
            detail = end?.let { "A ${intensity.label.lowercase()} spell may ease by +${it} min" }
                ?: "${intensity.label} rain may continue beyond 2 hours",
            arrivalMinutes = arrival,
            endMinutes = end,
            peakRateMmPerHour = peak,
            peakProbabilityPercent = peakProbability,
            intensity = intensity,
        )
    }

    fun intensityFor(rateMmPerHour: Double): RainIntensity = when {
        rateMmPerHour < 2.0 -> RainIntensity.LIGHT
        rateMmPerHour < 8.0 -> RainIntensity.MODERATE
        else -> RainIntensity.HEAVY
    }
}
