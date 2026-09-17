package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarNowcastTest {
    @Test
    fun mercatorBoundsAndDisplacementPreserveDirections() {
        val center = GeoPoint(51.5, -0.1)
        val bounds = WebMercator.imageBounds(center, zoom = 7, imageSize = 512)
        assertTrue(bounds.topLeft.latitude > center.latitude)
        assertTrue(bounds.bottomLeft.latitude < center.latitude)
        assertTrue(bounds.topRight.longitude > center.longitude)
        assertTrue(bounds.topLeft.longitude < center.longitude)

        val east = WebMercator.displacedCenter(center, 7, 10.0, 0.0)
        val north = WebMercator.displacedCenter(center, 7, 0.0, -10.0)
        assertTrue(east.longitude > center.longitude)
        assertTrue(north.latitude > center.latitude)
        val polar = WebMercator.imageBounds(GeoPoint(85.0, 179.5), 7, 512)
        assertTrue(polar.topLeft.latitude <= WebMercator.MAX_LATITUDE)
        assertTrue(polar.topRight.longitude > polar.topLeft.longitude)
    }

    @Test
    fun regionalBoundsAreFourTimesWiderAndShareCenterWithDetail() {
        val center = GeoPoint(51.5, -0.1)
        val regional = WebMercator.imageBounds(center, zoom = 5, imageSize = 512)
        val detail = WebMercator.imageBounds(center, zoom = 7, imageSize = 512)
        val regionalWidth = regional.topRight.longitude - regional.topLeft.longitude
        val detailWidth = detail.topRight.longitude - detail.topLeft.longitude
        assertEquals(4.0, regionalWidth / detailWidth, 0.0001)
        assertEquals(
            (detail.topLeft.longitude + detail.topRight.longitude) / 2.0,
            (regional.topLeft.longitude + regional.topRight.longitude) / 2.0,
            0.0001,
        )
        val converted = WebMercator.convertPixelDisplacement(8.0, -4.0, 7, 5)
        assertEquals(2.0, converted.first, 0.0)
        assertEquals(-1.0, converted.second, 0.0)
        val detailMoved = WebMercator.displacedCenter(center, 7, 8.0, -4.0)
        val regionalMoved = WebMercator.displacedCenter(center, 5, 2.0, -1.0)
        assertEquals(detailMoved.longitude, regionalMoved.longitude, 0.000001)
        assertEquals(detailMoved.latitude, regionalMoved.latitude, 0.000001)
    }

    @Test
    fun estimatesCardinalTranslations() {
        assertMotion(3, 0, expectedBearing = 90.0)
        assertMotion(-3, 0, expectedBearing = 270.0)
        assertMotion(0, -3, expectedBearing = 0.0)
        assertMotion(0, 3, expectedBearing = 180.0)
    }

    @Test
    fun robustlyAggregatesSeveralRecentPairs() {
        val base = square()
        val frames = listOf(
            TimedIntensityGrid(0, base),
            TimedIntensityGrid(600, translate(base, 2, -1)),
            TimedIntensityGrid(1200, translate(base, 4, -2)),
            TimedIntensityGrid(1800, translate(base, 6, -3)),
        )
        val estimate = RadarMotionEstimator.estimate(frames)
        assertNotNull(estimate)
        estimate!!
        assertEquals(0.2, estimate.dxPixelsPerMinute, 0.02)
        assertEquals(-0.1, estimate.dyPixelsPerMinute, 0.02)
        assertTrue(estimate.confidence >= 0.42)
        assertTrue(estimate.pairsUsed >= 2)
    }

    @Test
    fun rejectsBlankSparseAndImplausibleMotion() {
        val blank = IntensityGrid(32, 32, FloatArray(32 * 32))
        assertNull(
            RadarMotionEstimator.estimate(
                listOf(TimedIntensityGrid(0, blank), TimedIntensityGrid(600, blank)),
            ),
        )
        val sparseValues = FloatArray(32 * 32).also { it[10 * 32 + 10] = 1f }
        val sparse = IntensityGrid(32, 32, sparseValues)
        assertNull(
            RadarMotionEstimator.estimate(
                listOf(TimedIntensityGrid(0, sparse), TimedIntensityGrid(600, sparse)),
            ),
        )
        val base = square()
        assertNull(
            RadarMotionEstimator.estimate(
                listOf(
                    TimedIntensityGrid(0, base),
                    TimedIntensityGrid(60, translate(base, 8, 0)),
                ),
                maximumSpeedPixelsPerMinute = 3.0,
            ),
        )
        val noiseA = IntensityGrid(
            32,
            32,
            FloatArray(32 * 32) { if ((it * 37 + 5) % 13 < 4) 1f else 0f },
        )
        val noiseB = IntensityGrid(
            32,
            32,
            FloatArray(32 * 32) { if ((it * 19 + 11) % 17 < 5) 1f else 0f },
        )
        assertNull(
            RadarMotionEstimator.estimate(
                listOf(TimedIntensityGrid(0, noiseA), TimedIntensityGrid(600, noiseB)),
            ),
        )
    }

    @Test
    fun continuousTimelineBracketsBoundariesAndForecast() {
        val times = listOf(1_000L, 1_600L, 2_200L)
        assertEquals(
            RadarTimelineBracket(0, 1, 0.5, false, 0.0),
            RadarTimeline.bracket(times, 1_300.0),
        )
        val boundary = RadarTimeline.bracket(times, 1_600.0)
        assertEquals(0, boundary.firstIndex)
        assertEquals(1, boundary.secondIndex)
        assertEquals(1.0, boundary.fraction, 0.0)
        val now = RadarTimeline.bracket(times, 2_200.0)
        assertEquals(false, now.isForecast)
        val future = RadarTimeline.bracket(times, 4_000.0)
        assertEquals(true, future.isForecast)
        assertEquals(30.0, future.forecastMinutes, 0.0)
        val horizon = RadarTimeline.bracket(times, 8_000.0)
        assertEquals(60.0, horizon.forecastMinutes, 0.0)
        val beyondHorizon = RadarTimeline.bracket(times, Double.POSITIVE_INFINITY)
        assertEquals(false, beyondHorizon.isForecast)
        assertEquals(2, beyondHorizon.firstIndex)
        assertEquals(0.0, beyondHorizon.fraction, 0.0)
        val invalid = RadarTimeline.bracket(times, Double.NaN)
        assertEquals(false, invalid.isForecast)
        assertEquals(2, invalid.firstIndex)
    }

    private fun assertMotion(dx: Int, dy: Int, expectedBearing: Double) {
        val before = square()
        val after = translate(before, dx, dy)
        val estimate = RadarMotionEstimator.estimate(
            listOf(TimedIntensityGrid(0, before), TimedIntensityGrid(600, after)),
        )
        assertNotNull(estimate)
        estimate!!
        assertEquals(dx / 10.0, estimate.dxPixelsPerMinute, 0.02)
        assertEquals(dy / 10.0, estimate.dyPixelsPerMinute, 0.02)
        assertEquals(expectedBearing, estimate.bearingDegrees, 1.0)
        assertTrue(estimate.confidence >= 0.42)
    }

    private fun square(): IntensityGrid {
        val values = FloatArray(32 * 32)
        for (y in 10 until 17) for (x in 10 until 17) values[y * 32 + x] = 1f
        return IntensityGrid(32, 32, values)
    }

    private fun translate(grid: IntensityGrid, dx: Int, dy: Int): IntensityGrid {
        val values = FloatArray(grid.values.size)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val targetX = x + dx
                val targetY = y + dy
                if (targetX in 0 until grid.width && targetY in 0 until grid.height) {
                    values[targetY * grid.width + targetX] = grid[x, y]
                }
            }
        }
        return IntensityGrid(grid.width, grid.height, values)
    }
}
