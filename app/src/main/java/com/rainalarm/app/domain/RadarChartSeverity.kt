package com.rainalarm.app.domain

/** Chart-only severity. Rain decisions continue to use unmodified provider samples. */
object RadarChartSeverity {
    private val regional = floatArrayOf(
        RAIN_INTENSITY_THRESHOLD, 83f / 255f, 123f / 255f, 153f / 255f, 1f,
    )
    private val open = floatArrayOf(
        OpenRadarColorScale.WET_SEVERITY_THRESHOLD,
        OpenRadarColorScale.severityForDbz(15f),
        OpenRadarColorScale.severityForDbz(30f),
        OpenRadarColorScale.severityForDbz(45f),
        1f,
    )
    private val chartLevels = floatArrayOf(0f, 0.08f, 1f / 3f, 2f / 3f, 1f)

    fun forSeries(value: Float, encoding: RadarIntensityEncoding): Float = when (encoding) {
        RadarIntensityEncoding.REGIONAL_GRAYSCALE -> interpolate(value, regional)
        RadarIntensityEncoding.REGIONAL_AREA_CHART -> ((value - 0.25f) / 0.75f).coerceIn(0f, 1f)
        RadarIntensityEncoding.OPEN_REFLECTIVITY -> interpolate(value, open)
        RadarIntensityEncoding.OPEN_ALPHA -> interpolate(RainAlarmPalette.fromOpenAlpha(value), regional)
    }

    private fun interpolate(value: Float, sourceLevels: FloatArray): Float {
        if (!value.isFinite() || value <= sourceLevels[0]) return 0f
        for (index in 1 until sourceLevels.size) {
            if (value <= sourceLevels[index]) {
                val fraction = (value - sourceLevels[index - 1]) /
                    (sourceLevels[index] - sourceLevels[index - 1])
                return chartLevels[index - 1] +
                    (chartLevels[index] - chartLevels[index - 1]) * fraction
            }
        }
        return 1f
    }
}
