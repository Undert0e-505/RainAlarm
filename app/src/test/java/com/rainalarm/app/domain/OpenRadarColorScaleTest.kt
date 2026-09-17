package com.rainalarm.app.domain

import com.rainalarm.app.alerts.RadarAlertEvaluation
import com.rainalarm.app.alerts.RainAlertDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRadarColorScaleTest {
    @Test
    fun `transparent and low coverage returns are not classified as rain`() {
        assertEquals(0f, OpenRadarColorScale.fromArgb(0x0088DDEE), 0f)
        assertTrue(OpenRadarColorScale.fromArgb(0xAACEC087.toInt()) < OpenRadarColorScale.WET_SEVERITY_THRESHOLD)
        assertTrue(OpenRadarColorScale.fromArgb(0x8088DDEE.toInt()) < OpenRadarColorScale.WET_SEVERITY_THRESHOLD)
    }

    @Test
    fun `opaque universal blue light moderate and intense colours have distinct severity`() {
        val light = OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt()) // scheme 2, 15 dBZ
        val moderate = OpenRadarColorScale.fromArgb(0xFF005588.toInt()) // 30 dBZ
        val intense = OpenRadarColorScale.fromArgb(0xFFFF4400.toInt()) // 45 dBZ
        assertEquals(0.3f, light, 0.001f)
        assertTrue(light >= OpenRadarColorScale.WET_SEVERITY_THRESHOLD && light < 1f / 3f)
        assertTrue(moderate in 1f / 3f..2f / 3f)
        assertTrue(intense > 2f / 3f)
        assertTrue(light < moderate && moderate < intense)
    }

    @Test
    fun `smoothed colour between source key entries remains an intermediate strength`() {
        val left = OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt())
        val middle = OpenRadarColorScale.fromArgb(0xFF44C0E7.toInt())
        val right = OpenRadarColorScale.fromArgb(0xFF00A3E0.toInt())
        assertTrue(middle > left)
        assertTrue(middle < right)
    }

    @Test
    fun `open forecast chart compass and alerts share calibrated light severity`() {
        val light = OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt())
        val grid = IntensityGrid(9, 9, FloatArray(81) { light })
        val scale = 4f
        val field = RadarVelocityField(
            1, 1,
            byteArrayOf(RadarVelocityField.encodeChannel(0.0, scale),
                RadarVelocityField.encodeChannel(0.0, scale)),
            scale, 300, 0.9,
        )
        val series = OpenMinuteSeriesBuilder.fromDenseField(grid, field, 1_000, "open")
        assertEquals(RadarIntensityEncoding.OPEN_REFLECTIVITY, series.intensityEncoding)
        assertEquals(OpenRadarColorScale.WET_SEVERITY_THRESHOLD, series.wetThreshold, 0f)
        assertTrue(series.points.all { it.average < 1f / 3f })
        assertTrue(requireNotNull(RainMinuteSeriesAnalyzer.analyze(series)).rainingNow)
        assertTrue(RainAlertDecisionEngine.evaluateMinuteSeries(series) is RadarAlertEvaluation.WetNow)
        assertFalse(series.points.any { it.maximum >= 2f / 3f })
    }
}
