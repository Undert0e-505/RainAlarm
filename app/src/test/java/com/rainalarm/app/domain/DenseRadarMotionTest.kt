package com.rainalarm.app.domain

import com.rainalarm.app.alerts.RadarAlertEvaluation
import com.rainalarm.app.alerts.RainAlertDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseRadarMotionTest {
    @Test
    fun fieldCodecRoundTripsSignedDisplacements() {
        val scale = 72f
        listOf(-72.0, -18.0, 0.0, 21.0, 72.0).forEach { value ->
            val decoded = RadarVelocityField.decodeChannel(
                RadarVelocityField.encodeChannel(value, scale),
            ) * scale
            assertEquals(value, decoded, 0.6)
        }
    }

    @Test
    fun blockFlowRetainsSpatiallyDifferentMotionRegions() {
        val before = grid {
            rect(8, 15, 16, 23)
            rect(45, 36, 53, 44)
        }
        val after = grid {
            rect(11, 14, 19, 22) // right 3, up 1
            rect(42, 38, 50, 46) // left 3, down 2
        }
        val field = DenseRadarMotionEstimator.estimatePair(
            TimedIntensityGrid(0, before),
            TimedIntensityGrid(600, after),
            512,
            512,
        )
        assertNotNull(field)
        field!!
        val left = field.displacementAt(0.2, 0.3)
        val right = field.displacementAt(0.76, 0.64)
        assertTrue("left cell should move east", left.first > 5.0)
        assertTrue("right cell should move west", right.first < -5.0)
        assertTrue("regions should retain different horizontal vectors", left.first - right.first > 10.0)
    }

    @Test
    fun blankAndUnrelatedNoiseAreRejected() {
        val blank = IntensityGrid(64, 64, FloatArray(64 * 64))
        assertNull(
            DenseRadarMotionEstimator.estimatePair(
                TimedIntensityGrid(0, blank), TimedIntensityGrid(600, blank), 512, 512,
            ),
        )
        val noiseA = IntensityGrid(64, 64, FloatArray(64 * 64) { ((it * 37) % 101) / 100f })
        val noiseB = IntensityGrid(64, 64, FloatArray(64 * 64) { ((it * 71 + 13) % 103) / 102f })
        assertNull(
            DenseRadarMotionEstimator.estimatePair(
                TimedIntensityGrid(0, noiseA), TimedIntensityGrid(600, noiseB), 512, 512,
            ),
        )
    }

    @Test
    fun sixtyMinuteAdvectionIntegratesCachedField() {
        val field = uniformField(dx = 4.0, dy = -2.0)
        val source = DenseRadarAdvection.sourcePoint(0.5, 0.5, 60.0, field, 512, 512)
        assertEquals(256.0 - 24.0, source.first, 1.6)
        assertEquals(256.0 + 12.0, source.second, 1.6)
    }

    @Test
    fun denseAlertSamplesSameAdvectedPointAndRequiresDryNow() {
        val values = FloatArray(64 * 64)
        for (y in 29..35) for (x in 17..22) values[y * 64 + x] = 1f
        val latest = IntensityGrid(64, 64, values)
        val evaluation = RainAlertDecisionEngine.evaluateDenseField(
            latest,
            uniformField(dx = 4.0, dy = 0.0),
            frameIdentity = 999,
        )
        assertTrue("expected approaching, got $evaluation", evaluation is RadarAlertEvaluation.Approaching)
        evaluation as RadarAlertEvaluation.Approaching
        assertTrue(evaluation.etaStartMinutes in 1..60)

        val wetNow = latest.copy(values = values.copyOf().also { pixels ->
            for (y in 29..35) for (x in 29..35) pixels[y * 64 + x] = 1f
        })
        assertEquals(
            RadarAlertEvaluation.WetNow,
            RainAlertDecisionEngine.evaluateDenseField(wetNow, uniformField(4.0, 0.0), 999),
        )
    }

    private fun uniformField(dx: Double, dy: Double): RadarVelocityField {
        val channels = ByteArray(8 * 8 * 2)
        repeat(64) { index ->
            channels[index * 2] = RadarVelocityField.encodeChannel(dx, 72f)
            channels[index * 2 + 1] = RadarVelocityField.encodeChannel(dy, 72f)
        }
        return RadarVelocityField(8, 8, channels, 72f, 600, 0.8)
    }

    private fun grid(block: GridBuilder.() -> Unit): IntensityGrid =
        GridBuilder().apply(block).build()

    private class GridBuilder {
        private val values = FloatArray(64 * 64)
        fun rect(left: Int, top: Int, right: Int, bottom: Int) {
            for (y in top..bottom) for (x in left..right) values[y * 64 + x] = 1f
        }
        fun build() = IntensityGrid(64, 64, values)
    }
}
