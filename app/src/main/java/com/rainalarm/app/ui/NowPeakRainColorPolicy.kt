package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainAlarmPalette
import com.rainalarm.app.domain.RainMinuteAnalysis
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RadarChartSeverity
import com.rainalarm.app.domain.SnowAlarmPalette

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
        val peak = series.points.maxOf { it.maximum }
        val severity = series.chartSeverity(peak).coerceIn(0f, 1f)
        val paletteRaw = RadarChartSeverity.sharedPaletteIntensity(severity)
        val likelySnow = series.likelySnowFor(analysis)
        // The disc and pointer supply their own shared opacity, so retain the hue only.
        val opaqueArgb = (if (likelySnow) SnowAlarmPalette.colorAt(paletteRaw)
            else RainAlarmPalette.colorAt(paletteRaw)) or 0xFF000000.toInt()
        // Avoid a source-to-chart float round-off classifying an exact band anchor below its band.
        return NowPeakRainColor(severity,
            NowChartLayout.severityBand((severity + 0.000001f).coerceAtMost(1f)), opaqueArgb,
            likelySnow)
    }
}
