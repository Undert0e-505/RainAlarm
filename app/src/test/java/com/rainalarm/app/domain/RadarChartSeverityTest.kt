package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarChartSeverityTest {
    @Test
    fun `regional wet boundary remains WetNow but charts on baseline`() {
        val series = RainMinuteSeries(
            1_000,
            (0..60).map { RainMinutePoint(it, RAIN_INTENSITY_THRESHOLD,
                RAIN_INTENSITY_THRESHOLD, RAIN_INTENSITY_THRESHOLD, it > 0) },
            "regional", 1.0, RainMinuteAvailability.AVAILABLE,
        )
        assertTrue(requireNotNull(RainMinuteSeriesAnalyzer.analyze(series)).rainingNow)
        assertEquals(0f, series.chartSeverity(series.points.first().average), 0f)
        assertEquals(100f, NowVisualGeometry.chartY(series.chartSeverity(series.points.first().average), 100f), 0f)
    }

    @Test
    fun `regional pale cyan begins low Light and stronger colours reach higher bands`() {
        val encoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE
        assertEquals(0f, RadarChartSeverity.forSeries(0f, encoding), 0f)
        assertEquals(0.08f, RadarChartSeverity.forSeries(83f / 255f, encoding), 0.00001f)
        assertEquals(1f / 3f, RadarChartSeverity.forSeries(123f / 255f, encoding), 0.00001f)
        assertEquals(2f / 3f, RadarChartSeverity.forSeries(153f / 255f, encoding), 0.00001f)
        assertEquals(1f, RadarChartSeverity.forSeries(1f, encoding), 0f)
    }

    @Test
    fun `open opaque light blue starts low while moderate and intense remain distinct`() {
        val encoding = RadarIntensityEncoding.OPEN_REFLECTIVITY
        val light = OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt())
        val moderate = OpenRadarColorScale.fromArgb(0xFF005588.toInt())
        val intense = OpenRadarColorScale.fromArgb(0xFFFF4400.toInt())
        assertEquals(0f, RadarChartSeverity.forSeries(OpenRadarColorScale.WET_SEVERITY_THRESHOLD, encoding), 0f)
        assertEquals(0.08f, RadarChartSeverity.forSeries(light, encoding), 0.00001f)
        assertEquals(1f / 3f, RadarChartSeverity.forSeries(moderate, encoding), 0.00001f)
        assertTrue(RadarChartSeverity.forSeries(intense, encoding) >= 2f / 3f)
        assertTrue(RadarChartSeverity.forSeries(light, encoding) < 1f / 3f)
    }

    @Test
    fun `all provider envelopes remain ordered monotonic and bounded`() {
        RadarIntensityEncoding.entries.forEach { encoding ->
            val values = (0..255).map { RadarChartSeverity.forSeries(it / 255f, encoding) }
            assertTrue(values.all { it in 0f..1f })
            assertTrue(values.zipWithNext().all { (a, b) -> a <= b })
            val minimum = RadarChartSeverity.forSeries(0.25f, encoding)
            val average = RadarChartSeverity.forSeries(0.5f, encoding)
            val maximum = RadarChartSeverity.forSeries(0.75f, encoding)
            assertTrue(minimum <= average && average <= maximum)
        }
    }
}
