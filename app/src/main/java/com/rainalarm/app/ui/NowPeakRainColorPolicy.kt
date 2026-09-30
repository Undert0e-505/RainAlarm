package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainMinuteAnalysis
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.WeatherPresentationPolicy

internal data class NowPeakRainColor(
    val chartSeverity: Float,
    val band: Int,
    val argb: Int,
    val likelySnow: Boolean = false,
)

/** Convert each provider's upper forecast envelope to the shared visible rain palette. */
internal object NowPeakRainColorPolicy {
    fun forSeries(series: RainMinuteSeries, analysis: RainMinuteAnalysis): NowPeakRainColor? {
        if (!NowRainTexturePolicy.shouldShow(analysis.rainingNow, analysis.arrivalMinute) ||
            series.points.isEmpty()) return null
        val shared = WeatherPresentationPolicy.from(series, null)
        val severity = requireNotNull(shared.peakSeverity)
        val likelySnow = shared.kind == com.rainalarm.app.domain.PrecipitationPresentationKind.LIKELY_SNOW
        val opaqueArgb = requireNotNull(shared.peakColorArgb)
        // Avoid a source-to-chart float round-off classifying an exact band anchor below its band.
        return NowPeakRainColor(severity,
            NowChartLayout.severityBand((severity + 0.000001f).coerceAtMost(1f)), opaqueArgb,
            likelySnow)
    }
}
