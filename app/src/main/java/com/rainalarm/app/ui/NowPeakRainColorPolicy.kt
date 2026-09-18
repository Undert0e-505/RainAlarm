package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainAlarmPalette
import com.rainalarm.app.domain.RainMinuteAnalysis
import com.rainalarm.app.domain.RainMinuteSeries

internal data class NowPeakRainColor(val chartSeverity: Float, val band: Int, val argb: Int)

/** Convert each provider's upper forecast envelope to the shared visible rain palette. */
internal object NowPeakRainColorPolicy {
    private val severityStops = floatArrayOf(0f, 0.08f, 1f / 3f, 2f / 3f, 1f)
    private val paletteStops = floatArrayOf(63f, 83f, 123f, 153f, 173f)

    fun forSeries(series: RainMinuteSeries, analysis: RainMinuteAnalysis): NowPeakRainColor? {
        if (!NowRainTexturePolicy.shouldShow(analysis.rainingNow, analysis.arrivalMinute) ||
            series.points.isEmpty()) return null
        val peak = series.points.maxOf { it.maximum }
        val severity = series.chartSeverity(peak).coerceIn(0f, 1f)
        val paletteRaw = paletteRawForSeverity(severity)
        // The disc and pointer supply their own shared opacity, so retain the hue only.
        val opaqueArgb = RainAlarmPalette.colorAt(paletteRaw / 255f) or 0xFF000000.toInt()
        // Avoid a source-to-chart float round-off classifying an exact band anchor below its band.
        return NowPeakRainColor(severity,
            NowChartLayout.severityBand((severity + 0.000001f).coerceAtMost(1f)), opaqueArgb)
    }

    private fun paletteRawForSeverity(severity: Float): Float {
        for (index in 1 until severityStops.size) {
            if (severity <= severityStops[index]) {
                val fraction = ((severity - severityStops[index - 1]) /
                    (severityStops[index] - severityStops[index - 1])).coerceIn(0f, 1f)
                return paletteStops[index - 1] + (paletteStops[index] - paletteStops[index - 1]) * fraction
            }
        }
        return paletteStops.last()
    }
}
