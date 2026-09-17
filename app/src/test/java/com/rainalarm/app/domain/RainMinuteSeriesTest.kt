package com.rainalarm.app.domain

import com.rainalarm.app.alerts.RadarAlertEvaluation
import com.rainalarm.app.alerts.RainAlertDecisionEngine
import com.rainalarm.app.data.RadarPointSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainMinuteSeriesTest {
    @Test
    fun `palette anchors are exact continuous and opacity is monotonic`() {
        assertEquals(0, RainAlarmPalette.alphaAt(0f))
        RainAlarmPalette.stops.forEach { stop -> assertEquals(stop.argb, RainAlarmPalette.colorAt(stop.intensity)) }
        val alphas = (0..100).map { RainAlarmPalette.alphaAt(it / 100f) }
        assertTrue(alphas.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(0, RainAlarmPalette.alphaAt(52f / 255f))
        assertTrue(RainAlarmPalette.alphaAt(63f / 255f) > 0)
        assertTrue(RainAlarmPalette.alphaAt(93f / 255f) > RainAlarmPalette.alphaAt(63f / 255f))
        assertTrue(RainAlarmPalette.alphaAt(63f / 255f) < 38)
        assertEquals(0x4C83ECFF, RainAlarmPalette.colorAt(83f / 255f))
        assertEquals(0xFF0077F7.toInt(), RainAlarmPalette.colorAt(123f / 255f))
        assertEquals(0xFF1F0000.toInt(), RainAlarmPalette.colorAt(1f))
    }

    @Test
    fun `regional opacity ramp has no threshold jump haze or high intensity regression`() {
        assertTrue((0..52).all { RainAlarmPalette.alphaAt(it / 255f) == 0 })
        val alphas = (52..83).map { RainAlarmPalette.alphaAt(it / 255f) }
        assertTrue(alphas.zipWithNext().all { (a, b) -> b >= a && b - a <= 5 })
        assertTrue(RainAlarmPalette.alphaAt(62f / 255f) < RainAlarmPalette.alphaAt(63f / 255f))
        assertEquals(76, RainAlarmPalette.alphaAt(83f / 255f))
        assertEquals(255, RainAlarmPalette.alphaAt(100f / 255f))
        assertEquals(63f / 255f, RAIN_INTENSITY_THRESHOLD, 0f)
        assertTrue(RainAlarmPalette.alphaAt(RAIN_INTENSITY_THRESHOLD) > 0)
    }

    @Test
    fun `open alpha is mapped separately and retains visible light rain`() {
        assertEquals(0f, RainAlarmPalette.fromOpenAlpha(0.119f), 0f)
        assertEquals(63f / 255f, RainAlarmPalette.fromOpenAlpha(0.12f), 0.000001f)
        assertEquals(1f, RainAlarmPalette.fromOpenAlpha(1f), 0f)
        assertEquals(0x2683ECFF, RainAlarmPalette.colorAtOpen(RainAlarmPalette.fromOpenAlpha(0.12f)))
        assertTrue(RainAlarmPalette.colorAtOpen(RainAlarmPalette.fromOpenAlpha(0.2f)).ushr(24) > 0)
        val points = (0..60).map { minute ->
            val value = if (minute in 10..20) 0.12f else 0.119f
            RainMinutePoint(minute, value, value, value, minute > 0)
        }
        val series = series(points).copy(intensityEncoding = RadarIntensityEncoding.OPEN_ALPHA)
        assertEquals(OPEN_RAIN_INTENSITY_THRESHOLD, series.wetThreshold, 0f)
        assertEquals(0x2683ECFF, series.displayColor(0.12f))
        assertEquals(10, RainMinuteSeriesAnalyzer.analyze(series)?.arrivalMinute)
        assertEquals(21, RainMinuteSeriesAnalyzer.analyze(series)?.endMinute)
        assertTrue(RainAlertDecisionEngine.evaluateMinuteSeries(series) is RadarAlertEvaluation.Approaching)
    }

    @Test
    fun `hardware linear sampling contract is bounded symmetric and preserves constants`() {
        assertEquals(1, RadarLinearSampling.GPU_TEXTURE_TAPS)
        assertEquals(0.42f, RadarLinearSampling.bilinear(FloatArray(4) { 0.42f }, 2, 2, 0.4, 0.6), 0.00001f)
        val edge = floatArrayOf(0f, 1f, 0f, 1f)
        val left = RadarLinearSampling.bilinear(edge, 2, 2, 0.25, 0.5)
        val right = RadarLinearSampling.bilinear(edge, 2, 2, 0.75, 0.5)
        assertEquals(0.25f, left, 0.00001f)
        assertEquals(0.75f, right, 0.00001f)
        assertEquals(1f, left + right, 0.00001f)
    }

    @Test
    fun `subtexel linear ramp keeps intermediate levels before low precision quantization`() {
        val edge = floatArrayOf(0f, 1f)
        val continuous = (0..40).map { step ->
            RadarLinearSampling.bilinear(edge, 2, 1, step / 40.0, 0.0)
        }
        val simulatedQuarterTexel = (0..40).map { step ->
            val rounded = kotlin.math.round(step / 10f) / 4.0
            RadarLinearSampling.bilinear(edge, 2, 1, rounded, 0.0)
        }
        assertEquals(41, continuous.distinct().size)
        assertTrue(simulatedQuarterTexel.distinct().size <= 5)
    }

    @Test
    fun `provider samples interpolate exact minute checkpoints through sixty`() {
        val samples = listOf(
            sample(1_000, false, 0f),
            sample(1_300, true, 0.5f),
            sample(1_900, true, 0.75f),
            sample(2_800, true, 0.25f),
            sample(4_600, true, 1f),
        )
        val series = ProviderMinuteSeriesBuilder.fromSamples(samples, "provider", 1_000)
        assertEquals(RainMinuteAvailability.AVAILABLE, series.availability)
        assertEquals(61, series.points.size)
        assertEquals(0f, series.points[0].average, 0f)
        assertEquals(0.1f, series.points[1].average, 0.0001f)
        assertEquals(0.75f, series.points[15].average, 0f)
        assertEquals(0.25f, series.points[30].average, 0f)
        assertEquals(1f, series.points[60].average, 0f)
    }

    @Test
    fun `delayed observation still anchors Now to wall clock and uses available forecast`() {
        val samples = listOf(sample(1_000, false, 0f), sample(2_800, true, 0.4f),
            sample(4_600, true, 0.8f), sample(6_400, true, 1f))
        val series = ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 2_800)
        assertEquals(2_800L, series.startEpochSeconds)
        assertEquals(1_000L, series.latestObservationEpochSeconds)
        assertEquals(RainMinuteAvailability.AVAILABLE, series.availability)
        assertEquals(0.4f, series.points[0].average, 0.0001f)
        assertEquals(0.6f, series.points[15].average, 0.0001f)
        assertEquals(0.8f, series.points[30].average, 0.0001f)
        assertEquals(1f, series.points[60].average, 0.0001f)
        assertTrue(series.points.first().forecast)
    }

    @Test
    fun `moving front remains dry at marker now and arrives five minutes later`() {
        fun front(lastWetX: Int): RegionalPointPatch {
            val values = FloatArray(17 * 5) { index -> if (index % 17 <= lastWetX) 0.5f else 0f }
            return RegionalPointPatch(17, 5, 0, 0, 17, 5, 8.5, 2.5, values)
        }
        val samples = listOf(
            com.rainalarm.app.data.RadarPointSample(1_000, false, 0f, 0f, 0.5f, 1f, 0f, front(6)),
            com.rainalarm.app.data.RadarPointSample(1_300, true, 0f, 0f, 0.5f, 1f, 0f, front(6)),
            com.rainalarm.app.data.RadarPointSample(1_600, true, 0f, 0f, 0.5f, 1f, 0f, front(7)),
            com.rainalarm.app.data.RadarPointSample(1_900, true, 0.5f, 0f, 0.5f, 1f, 0f, front(8)),
        )
        val now = 1_480L // observation eight minutes old; forecast crosses the marker at +5
        val series = ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", now)
        assertEquals(RainMinuteAvailability.PARTIAL, series.availability)
        assertEquals(0f, series.points.first().average, 0.0001f)
        assertEquals(0, RainAlarmPalette.alphaAt(series.points.first().average))
        assertTrue(series.points[4].average < RAIN_INTENSITY_THRESHOLD)
        assertTrue(series.points[5].average >= RAIN_INTENSITY_THRESHOLD)
        assertTrue(RainAlarmPalette.alphaAt(series.points[5].average) > 0)
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series))
        assertFalse(analysis.rainingNow)
        assertEquals(5, analysis.arrivalMinute)
        assertEquals(5, (RainAlertDecisionEngine.evaluateMinuteSeries(series)
            as RadarAlertEvaluation.Approaching).etaStartMinutes)
        val expired = ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 1_960L)
        assertEquals(RainMinuteAvailability.UNAVAILABLE, expired.availability)
        assertEquals(RadarAlertEvaluation.Unknown, RainAlertDecisionEngine.evaluateMinuteSeries(expired))
    }

    @Test
    fun `second forecast bracket uses its own local velocity instead of neutral motion`() {
        val values = FloatArray(17 * 5) { index -> if (index % 17 <= 7) 0.5f else 0f }
        val patch = RegionalPointPatch(17, 5, 0, 0, 17, 5, 8.5, 2.5, values)
        fun sample(time: Long, forecast: Boolean, velocity: Float?) =
            RadarPointSample(time, forecast, 0f, 0f, 0.5f, velocity, 0f, patch)
        val samples = listOf(
            sample(1_000, false, 0f),
            sample(1_300, true, 0f),
            sample(1_600, true, 4f),
            sample(1_900, true, null),
        )
        val actual = ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 1_750)
        val missingSecondVelocity = ProviderMinuteSeriesBuilder.fromSamples(
            samples.map { if (it.time == 1_600L) it.copy(localVelocityX = null) else it },
            "regional", 1_750,
        )
        assertEquals(0.25f, actual.points.first().average, 0.0001f)
        assertEquals(0f, missingSecondVelocity.points.first().average, 0.0001f)
        assertTrue(requireNotNull(RainMinuteSeriesAnalyzer.analyze(actual)).rainingNow)
        assertFalse(requireNotNull(RainMinuteSeriesAnalyzer.analyze(missingSecondVelocity)).rainingNow)
    }

    @Test
    fun `short horizon is partial and missing minutes are not padded or treated as clear`() {
        val samples = listOf(sample(1_000, false, 0f), sample(2_800, true, 0f),
            sample(4_600, true, 0f), sample(4_900, true, 0f))
        val series = ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 2_800)
        assertEquals(RainMinuteAvailability.PARTIAL, series.availability)
        assertEquals(36, series.points.size)
        assertEquals(35, series.points.last().minute)
        assertEquals(RadarAlertEvaluation.Unknown, RainAlertDecisionEngine.evaluateMinuteSeries(series))
        val arriving = ProviderMinuteSeriesBuilder.fromSamples(
            samples.map { if (it.time >= 4_600) it.copy(intensity = 0.4f, maximum = 0.4f) else it },
            "regional", 2_800,
        )
        assertTrue(RainAlertDecisionEngine.evaluateMinuteSeries(arriving) is RadarAlertEvaluation.Approaching)
    }

    @Test
    fun `stale or missing current forecast is unavailable rather than dry`() {
        val samples = listOf(sample(1_000, false, 0f), sample(2_800, true, 0f))
        assertEquals(RainMinuteAvailability.UNAVAILABLE,
            ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 5_000).availability)
        assertEquals(RainMinuteAvailability.UNAVAILABLE,
            ProviderMinuteSeriesBuilder.fromSamples(samples, "regional", 2_900).availability)
    }

    @Test
    fun `arrival and end use visible-light-rain intensity threshold`() {
        val points = (0..60).map { minute ->
            val value = when (minute) { in 15..29 -> RAIN_INTENSITY_THRESHOLD; else -> 62f / 255f }
            RainMinutePoint(minute, value, value, value, minute > 0)
        }
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series(points)))
        assertFalse(analysis.rainingNow)
        assertEquals(15, analysis.arrivalMinute)
        assertEquals(30, analysis.endMinute)
        val dry = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series(points.map { it.copy(minimum = 0f, average = 0f, maximum = 0f) })))
        assertNull(dry.arrivalMinute)
        assertNull(RainMinuteSeriesAnalyzer.analyze(RainMinuteSeries.unavailable(1_000, "x", "no motion")))
        assertEquals(
            RadarAlertEvaluation.Approaching(15, 29, 1_000,
                confirmedDurationMinutes = 15,
                confirmedPeakIntensity = RAIN_INTENSITY_THRESHOLD,
                expectedStartEpochSeconds = 1_900),
            RainAlertDecisionEngine.evaluateMinuteSeries(series(points)),
        )
    }

    @Test
    fun `travel bearing converts to source bearing and cardinals`() {
        assertEquals(90.0, requireNotNull(bearingForVector(2.0, 0.0)), 0.001)
        val points = (0..60).map { RainMinutePoint(it, 0f, 0f, 0f, it > 0) }
        val series = RainMinuteSeries(1_000, points, "x", 1.0, RainMinuteAvailability.AVAILABLE,
            travelBearingDegrees = 90.0)
        assertEquals(270.0, requireNotNull(series.sourceBearingDegrees), 0.001)
        assertEquals("W", cardinalDirection(requireNotNull(series.sourceBearingDegrees)))
        assertNull(bearingForVector(0.0, 0.0))
    }

    @Test
    fun `open dense series and alert evaluation share the same timeline`() {
        val grid = IntensityGrid(9, 9, FloatArray(81).apply {
            for (y in 2..6) for (x in 0..2) this[y * 9 + x] = 0.8f
        })
        val scale = 4f
        val field = RadarVelocityField(
            1, 1,
            byteArrayOf(RadarVelocityField.encodeChannel(1.0, scale), RadarVelocityField.encodeChannel(0.0, scale)),
            scale, 300, 0.9,
        )
        val open = OpenMinuteSeriesBuilder.fromDenseField(grid, field, 1_000, "open")
        assertEquals(61, open.points.size)
        assertEquals(0.9, open.confidence, 0.0)
        val evaluation = RainAlertDecisionEngine.evaluateMinuteSeries(open)
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(open))
        assertEquals(analysis.rainingNow, evaluation is RadarAlertEvaluation.WetNow)
        val unavailable = OpenMinuteSeriesBuilder.fromDenseField(grid, field.copy(confidence = 0.1), 1_000, "open")
        assertEquals(RainMinuteAvailability.UNAVAILABLE, unavailable.availability)
        val delayed = OpenMinuteSeriesBuilder.fromDenseField(grid, field, 1_000, "open", 1_300)
        assertEquals(1_300L, delayed.startEpochSeconds)
        assertEquals(1_000L, delayed.latestObservationEpochSeconds)
        assertTrue(delayed.points.first().forecast)
        assertEquals(RainMinuteAvailability.UNAVAILABLE,
            OpenMinuteSeriesBuilder.fromDenseField(grid, field, 1_000, "open", 2_000).availability)
    }

    @Test
    fun `open bearing uses the local dense cell rather than a global vector`() {
        val scale = 4f
        val field = RadarVelocityField(
            2, 1,
            byteArrayOf(
                RadarVelocityField.encodeChannel(2.0, scale), RadarVelocityField.encodeChannel(0.0, scale),
                RadarVelocityField.encodeChannel(-2.0, scale), RadarVelocityField.encodeChannel(0.0, scale),
            ),
            scale, 300, 0.9,
        )
        val series = OpenMinuteSeriesBuilder.fromDenseField(
            IntensityGrid(5, 5, FloatArray(25)), field, 1_000, "open",
        )
        assertEquals(270.0, requireNotNull(series.travelBearingDegrees), 1.0)
        assertEquals(90.0, requireNotNull(series.sourceBearingDegrees), 1.0)
    }

    @Test
    fun `five by five compact envelope retains minimum average and maximum`() {
        val values = FloatArray(25) { it / 24f }
        val envelope = IntensityGrid(5, 5, values).envelopeAround(2.0, 2.0)
        assertEquals(0f, envelope.minimum, 0f)
        assertEquals(0.5f, envelope.average, 0.0001f)
        assertEquals(1f, envelope.maximum, 0f)
    }

    @Test
    fun `compact samples retain envelopes and no raster`() {
        val sample = RadarPointSample(1_000, false, 0.4f, 0.1f, 0.8f, 2f, 0f)
        assertEquals(0.1f, sample.minimum, 0f)
        assertEquals(0.4f, sample.intensity, 0f)
        assertEquals(0.8f, sample.maximum, 0f)
        assertFalse(sample.javaClass.declaredFields.any { it.type.name.contains("Bitmap") || it.type.isArray })
        assertEquals(90.0, requireNotNull(sample.travelBearingDegrees), 0.001)
    }

    @Test
    fun `compass and chart geometry preserve north and endpoints`() {
        val north = NowVisualGeometry.compassPoint(0.0, 100f)
        assertEquals(0f, north.first, 0.001f)
        assertEquals(-100f, north.second, 0.001f)
        assertEquals(0f, NowVisualGeometry.chartX(0, 300f), 0f)
        assertEquals(300f, NowVisualGeometry.chartX(60, 300f), 0f)
        assertEquals(0f, NowVisualGeometry.chartY(1f, 200f), 0f)
        assertEquals(200f, NowVisualGeometry.chartY(0f, 200f), 0f)
    }

    private fun sample(time: Long, forecast: Boolean, average: Float) =
        RadarPointSample(time, forecast, average, average * 0.5f, average)

    private fun series(points: List<RainMinutePoint>) = RainMinuteSeries(
        1_000, points, "provider", 1.0, RainMinuteAvailability.AVAILABLE,
    )
}
