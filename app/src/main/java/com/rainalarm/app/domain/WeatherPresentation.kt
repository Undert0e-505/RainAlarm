package com.rainalarm.app.domain

import kotlin.math.roundToInt

/**
 * Pure, provider-normalized presentation shared by Now and the home-screen widget.
 * Android/Compose wording and drawing remain consumers of this model rather than owning
 * precipitation thresholds or clear/unknown decisions.
 */
enum class PrecipitationPresentationKind { RAIN, LIKELY_SNOW, CLEAR, UNKNOWN }

enum class QualitativeIntensity { LIGHT, MEDIUM, SEVERE }

data class MiniCompassPresentation(
    val kind: PrecipitationPresentationKind,
    val centerText: String,
    val centerUnit: String? = null,
    val rainingNow: Boolean = false,
    val arrivalMinute: Int? = null,
    val confirmedStopMinute: Int? = null,
    val peakSeverity: Float? = null,
    val peakColorArgb: Int? = null,
    val qualitativeIntensity: QualitativeIntensity? = null,
    val sourceBearingDegrees: Double? = null,
    val knownHorizonMinutes: Int = 0,
    val completeHour: Boolean = false,
) {
    val hasPrecipitation: Boolean
        get() = kind == PrecipitationPresentationKind.RAIN ||
            kind == PrecipitationPresentationKind.LIKELY_SNOW
}

object WeatherPresentationPolicy {
    fun from(series: RainMinuteSeries?, temperatureC: Double?): MiniCompassPresentation {
        if (series == null || series.availability == RainMinuteAvailability.UNAVAILABLE ||
            series.points.isEmpty()
        ) return unknown()
        val analysis = RainMinuteSeriesAnalyzer.analyze(series) ?: return unknown()
        val horizon = series.points.last().minute
        val complete = series.availability == RainMinuteAvailability.AVAILABLE && horizon == 60
        val arrival = analysis.arrivalMinute
        val hasPrecipitation = analysis.rainingNow || arrival != null
        if (!hasPrecipitation) {
            if (!complete) return unknown(horizon)
            return MiniCompassPresentation(
                kind = PrecipitationPresentationKind.CLEAR,
                centerText = temperatureC?.takeIf(Double::isFinite)?.roundToInt()?.let { "$it°" } ?: "—",
                knownHorizonMinutes = horizon,
                completeHour = true,
            )
        }
        val likelySnow = series.likelySnowFor(analysis)
        val peak = series.points.maxOf { it.maximum }
        val severity = series.chartSeverity(peak).coerceIn(0f, 1f)
        val sharedRaw = RadarChartSeverity.sharedPaletteIntensity(severity)
        val colour = (if (likelySnow) SnowAlarmPalette.colorAt(sharedRaw)
            else RainAlarmPalette.colorAt(sharedRaw)) or 0xFF000000.toInt()
        return MiniCompassPresentation(
            kind = if (likelySnow) PrecipitationPresentationKind.LIKELY_SNOW
                else PrecipitationPresentationKind.RAIN,
            centerText = if (analysis.rainingNow) "Now" else requireNotNull(arrival).toString(),
            centerUnit = if (analysis.rainingNow) null else "min",
            rainingNow = analysis.rainingNow,
            arrivalMinute = arrival,
            confirmedStopMinute = analysis.endMinute,
            peakSeverity = severity,
            peakColorArgb = colour,
            qualitativeIntensity = qualitative(severity),
            sourceBearingDegrees = series.sourceBearingDegrees,
            knownHorizonMinutes = horizon,
            completeHour = complete,
        )
    }

    fun qualitative(severity: Float): QualitativeIntensity = when {
        severity >= 2f / 3f -> QualitativeIntensity.SEVERE
        severity >= 1f / 3f -> QualitativeIntensity.MEDIUM
        else -> QualitativeIntensity.LIGHT
    }

    private fun unknown(horizon: Int = 0) = MiniCompassPresentation(
        kind = PrecipitationPresentationKind.UNKNOWN,
        centerText = "—",
        knownHorizonMinutes = horizon,
        completeHour = false,
    )
}
