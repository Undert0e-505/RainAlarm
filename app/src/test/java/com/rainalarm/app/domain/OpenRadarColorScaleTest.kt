package com.rainalarm.app.domain

import com.rainalarm.app.alerts.RadarAlertEvaluation
import com.rainalarm.app.alerts.RainAlertDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRadarColorScaleTest {
    @Test
    fun `transparent pixels are dry while alpha never changes reflectivity strength`() {
        assertEquals(0f, OpenRadarColorScale.fromArgb(0x0088DDEE), 0f)
        assertTrue(OpenRadarColorScale.fromArgb(0xAA827B69.toInt()) < OpenRadarColorScale.WET_SEVERITY_THRESHOLD)
        assertEquals(
            OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt()),
            OpenRadarColorScale.fromArgb(0x8088DDEE.toInt()),
            0f,
        )
        val barelyCovered = OpenRadarColorScale.decode(0x0188DDEE, false)
        assertEquals(83f / 255f, barelyCovered.sharedIntensity, 0.001f)
        assertEquals(0f, barelyCovered.severity, 0f)
        assertEquals(0f, OpenRadarColorScale.fromArgb(0x0188DDEE), 0f)
    }

    @Test
    fun `every stored official universal blue rain anchor decodes monotonically`() {
        val anchors = listOf(
            -10 to 0x636159, -5 to 0x726E61, 0 to 0x827B69, 5 to 0x928871,
            10 to 0xCEC087, 14 to 0xDED097, 15 to 0x88DDEE, 20 to 0x00A3E0,
            25 to 0x0077AA, 30 to 0x005588, 34 to 0x004768, 35 to 0xFFEE00,
            40 to 0xFFAA00, 44 to 0xFF8100, 45 to 0xFF4400, 50 to 0xC10000,
            54 to 0x5D0000, 55 to 0xFFAAFF, 60 to 0xFF77FF, 64 to 0xFF4EFF,
            65 to 0xFFFFFF,
        )
        val decoded = anchors.map { (dbz, rgb) ->
            dbz to OpenRadarColorScale.decode((0xff shl 24) or rgb, true)
        }
        decoded.forEach { (dbz, value) ->
            assertEquals(OpenPrecipitationType.RAIN, value.type)
            assertEquals(OpenRadarColorScale.sharedIntensityForDbz(dbz.toFloat()), value.sharedIntensity, 0.015f)
        }
        assertTrue(decoded.zipWithNext().all { it.first.second.sharedIntensity <= it.second.second.sharedIntensity })
    }

    @Test
    fun `every stored official universal blue snow anchor decodes as likely snow monotonically`() {
        val anchors = listOf(
            -10 to 0xCFFFFF, 0 to 0xC7FFFF, 10 to 0xBFFFFF, 15 to 0x9FDFFF,
            20 to 0x7FBFFF, 25 to 0x5F9FFF, 30 to 0x4F8FFF, 35 to 0x3F7FFF,
            40 to 0x2F6FFF, 45 to 0x1F5FFF, 50 to 0x0F4FFF, 55 to 0x003FFF,
            60 to 0x002FFF, 65 to 0x001FFF,
        )
        val decoded = anchors.map { (dbz, rgb) ->
            dbz to OpenRadarColorScale.decode((0xff shl 24) or rgb, true)
        }
        decoded.forEach { (dbz, value) ->
            assertEquals(OpenPrecipitationType.LIKELY_SNOW, value.type)
            assertTrue(value.snowConfidence >= OpenRadarColorScale.SNOW_CLASSIFICATION_THRESHOLD)
            assertEquals(OpenRadarColorScale.sharedIntensityForDbz(dbz.toFloat()), value.sharedIntensity, 0.015f)
        }
        assertTrue(decoded.zipWithNext().all { it.first.second.sharedIntensity <= it.second.second.sharedIntensity })
        anchors.forEach { (_, rgb) ->
            assertEquals(OpenPrecipitationType.RAIN,
                OpenRadarColorScale.decode((0xff shl 24) or rgb, false).type)
        }
    }

    @Test
    fun `smoothed snow pixels retain type and intermediate intensity`() {
        val left = OpenRadarColorScale.decode(0xFF9FDFFF.toInt(), true)
        val middle = OpenRadarColorScale.decode(0xFF8FCFFF.toInt(), true)
        val right = OpenRadarColorScale.decode(0xFF7FBFFF.toInt(), true)
        assertEquals(OpenPrecipitationType.LIKELY_SNOW, middle.type)
        assertTrue(middle.sharedIntensity > left.sharedIntensity)
        assertTrue(middle.sharedIntensity < right.sharedIntensity)
    }

    @Test
    fun `snow palette shares intensity bands but not rain hues`() {
        for (raw in listOf(83f, 123f, 153f)) {
            val value = raw / 255f
            assertTrue(SnowAlarmPalette.colorAt(value) != RainAlarmPalette.colorAt(value))
            assertEquals(RainAlarmPalette.colorAt(value).ushr(24), SnowAlarmPalette.colorAt(value).ushr(24))
        }
        assertEquals(0, SnowAlarmPalette.colorAt(RainAlarmPalette.REGIONAL_TRACE_START_RAW / 255f))
    }

    @Test
    fun `opaque universal blue light moderate and intense colours have distinct severity`() {
        val light = OpenRadarColorScale.fromArgb(0xFF88DDEE.toInt()) // scheme 2, 15 dBZ
        val moderate = OpenRadarColorScale.fromArgb(0xFF005588.toInt()) // 30 dBZ
        val intense = OpenRadarColorScale.fromArgb(0xFFFF4400.toInt()) // 45 dBZ
        assertEquals(83f / 255f, light, 0.001f)
        assertTrue(light >= OpenRadarColorScale.WET_SEVERITY_THRESHOLD && light < 1f / 3f)
        assertEquals(1f / 3f, RadarChartSeverity.forSeries(moderate, RadarIntensityEncoding.OPEN_REFLECTIVITY), 0.001f)
        assertEquals(2f / 3f, RadarChartSeverity.forSeries(intense, RadarIntensityEncoding.OPEN_REFLECTIVITY), 0.001f)
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

    @Test
    fun `open series carries snow type without changing intensity bands`() {
        val light = OpenRadarColorScale.decode(0xFF9FDFFF.toInt(), true)
        val grid = IntensityGrid(9, 9, FloatArray(81) { light.severity })
        val snow = IntensityGrid(9, 9, FloatArray(81) { light.snowConfidence })
        val field = RadarVelocityField(
            1, 1,
            byteArrayOf(RadarVelocityField.encodeChannel(0.0, 4f),
                RadarVelocityField.encodeChannel(0.0, 4f)),
            4f, 300, 0.9,
        )
        val series = OpenMinuteSeriesBuilder.fromDenseField(grid, field, 1_000, "open", snowGrid = snow)
        assertTrue(series.points.all { it.likelySnow })
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series))
        assertTrue(series.likelySnowFor(analysis))
        assertTrue(series.chartSeverity(series.points.first().average) < 1f / 3f)
        assertTrue(series.precipitationColor(series.points.first().average, true) !=
            series.precipitationColor(series.points.first().average, false))
    }
}
