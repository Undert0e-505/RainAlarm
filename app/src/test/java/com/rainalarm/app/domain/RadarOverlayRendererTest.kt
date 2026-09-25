package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarOverlayRendererTest {
    private val times = listOf(0L, 600L, 1_200L)
    private val pairMotion = physicalMotion(0.24, -0.12)

    @Test
    fun observedFramesBlendAndWarpContinuouslyAtAllFractions() {
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { fraction ->
            val plan = RadarOverlayPlanner.plan(
                bracket = RadarTimelineBracket(0, 1, fraction, false, 0.0),
                tier = RadarResolutionTier.REGIONAL,
                pairMotions = listOf(pairMotion, null),
                frameTimes = times,
                futureMotion = pairMotion,
            )
            val fullDx = pairMotion.dxWorldFractionPerMinute * 10.0
            val fullDy = pairMotion.dyWorldFractionPerMinute * 10.0
            assertEquals(0, plan.firstIndex)
            assertEquals(1, plan.secondIndex)
            assertEquals(1.0 - fraction, plan.firstAlpha, 0.0)
            assertEquals(fraction, plan.secondAlpha, 0.0)
            assertEquals(fullDx * fraction, plan.firstDxWorldFraction, 1e-12)
            assertEquals(fullDy * fraction, plan.firstDyWorldFraction, 1e-12)
            assertEquals(-fullDx * (1.0 - fraction), plan.secondDxWorldFraction, 1e-12)
            assertEquals(-fullDy * (1.0 - fraction), plan.secondDyWorldFraction, 1e-12)
        }
    }

    @Test
    fun exactBoundaryAndMissingPairMotionRemainSmooth() {
        val exact = RadarOverlayPlanner.plan(
            RadarTimelineBracket(1, 1, 0.0, false, 0.0),
            RadarResolutionTier.DETAIL,
            listOf(null, null),
            times,
            null,
        )
        assertEquals(1, exact.firstIndex)
        assertEquals(1, exact.secondIndex)
        assertEquals(1.0, exact.firstAlpha, 0.0)
        assertEquals(0.0, exact.secondAlpha, 0.0)

        val crossfade = RadarOverlayPlanner.plan(
            RadarTimelineBracket(1, 2, 0.37, false, 0.0),
            RadarResolutionTier.DETAIL,
            listOf(null, null),
            times,
            null,
        )
        assertEquals(0.63, crossfade.firstAlpha, 1e-12)
        assertEquals(0.37, crossfade.secondAlpha, 1e-12)
        assertEquals(0.0, crossfade.firstDxWorldFraction, 0.0)
        assertEquals(0.0, crossfade.secondDyWorldFraction, 0.0)
    }

    @Test
    fun choosesExactlyOneTierWithRegionalFallback() {
        assertEquals(
            RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(5.0, regionalAvailable = true, detailAvailable = true),
        )
        assertEquals(
            RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(7.0, regionalAvailable = true, detailAvailable = true),
        )
        assertEquals(
            RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(12.0, regionalAvailable = true, detailAvailable = false),
        )
        assertEquals(
            RadarResolutionTier.DETAIL,
            RadarOverlayPlanner.activeTier(5.0, regionalAvailable = false, detailAvailable = true),
        )
    }

    @Test
    fun futureProjectionMovesContinuouslyAndReachesExactSixtyMinuteEndpoint() {
        val minutes = (1..600).map { it / 10.0 }
        val xPositions = minutes.map { minute ->
            val plan = RadarOverlayPlanner.plan(
                RadarTimelineBracket(2, 2, 0.0, true, minute),
                RadarResolutionTier.REGIONAL,
                listOf(null, null),
                times,
                pairMotion,
            )
            assertEquals(2, plan.firstIndex)
            assertEquals(1.0, plan.firstAlpha, 0.0)
            NorthUpRadarGeoreference.screenDisplacement(
                plan.firstDxWorldFraction,
                plan.firstDyWorldFraction,
                mapZoom = 7.0,
            ).dxPixels
        }
        assertTrue(xPositions.zipWithNext().all { (a, b) -> b > a })
        listOf(1.0, 15.0, 30.0, 60.0).forEach { checkpoint ->
            val index = (checkpoint * 10).toInt() - 1
            assertTrue(xPositions[index] > 0.0)
        }
        val expected = NorthUpRadarGeoreference.screenDisplacement(
            pairMotion.dxWorldFractionPerMinute * 60.0,
            pairMotion.dyWorldFractionPerMinute * 60.0,
            7.0,
        )
        assertEquals(expected.dxPixels, xPositions.last(), 1e-9)
    }

    @Test
    fun physicalMotionIsConsistentAcrossRegionalAndDetailTiers() {
        val regional = physicalMotion(0.25, -0.125, RadarResolutionTier.REGIONAL)
        val regionalPixels = regional.pixelsPerMinute(RadarResolutionTier.REGIONAL)
        val detailPixels = regional.pixelsPerMinute(RadarResolutionTier.DETAIL)
        assertEquals(regionalPixels.first * 4.0, detailPixels.first, 1e-12)
        assertEquals(regionalPixels.second * 4.0, detailPixels.second, 1e-12)

        val regionalBounds = WebMercator.coordinateImageBounds(GeoPoint(51.5, -0.1), 5, 512)
        val detailBounds = WebMercator.coordinateImageBounds(GeoPoint(51.5, -0.1), 7, 512)
        val regionalRect = NorthUpRadarGeoreference.screenRect(
            regionalBounds,
            GeoPoint(51.5, -0.1),
            7.0,
            1_000,
            800,
        )
        val detailRect = NorthUpRadarGeoreference.screenRect(
            detailBounds,
            GeoPoint(51.5, -0.1),
            7.0,
            1_000,
            800,
        )
        assertEquals(500.0, (regionalRect.left + regionalRect.right) / 2.0, 1e-6)
        assertEquals(400.0, (detailRect.top + detailRect.bottom) / 2.0, 1e-6)
        assertEquals(
            regionalRect.right - regionalRect.left,
            (detailRect.right - detailRect.left) * 4.0,
            1e-6,
        )
    }

    @Test
    fun sameGeographicEchoStaysFixedAcrossTierSwitchPanAndZoom() {
        val requestCenter = GeoPoint(57.25, -4.0)
        val echo = WebMercator.displacedCenter(requestCenter, 7, 36.0, -22.0)
        val regionalBounds = WebMercator.coordinateImageBounds(requestCenter, 5, 512)
        val detailBounds = WebMercator.coordinateImageBounds(requestCenter, 7, 512)

        fun renderedPoint(bounds: GeoQuad, sourceDx: Double, sourceDy: Double,
            camera: GeoPoint, zoom: Double): Pair<Double, Double> {
            val rect = NorthUpRadarGeoreference.screenRect(bounds, camera, zoom, 1_080, 720)
            return rect.left + (256.0 + sourceDx) / 512.0 * (rect.right - rect.left) to
                rect.top + (256.0 + sourceDy) / 512.0 * (rect.bottom - rect.top)
        }
        // The same world displacement is 4x fewer source pixels in the z5 image.
        listOf(
            requestCenter to 6.34,
            requestCenter to 6.36,
            GeoPoint(echo.latitude - 0.18, echo.longitude + 0.22) to 8.1,
        ).forEach { (camera, zoom) ->
            val regional = renderedPoint(regionalBounds, 18.0, -11.0, camera, zoom)
            val detail = renderedPoint(detailBounds, 72.0, -44.0, camera, zoom)
            assertEquals(regional.first, detail.first, 1e-5)
            assertEquals(regional.second, detail.second, 1e-5)
            val direct = NorthUpRadarGeoreference.screenRect(
                WebMercator.imageBounds(echo, 7, 1), camera, zoom, 1_080, 720,
            )
            assertEquals((direct.left + direct.right) / 2.0, detail.first, 0.15)
            assertEquals((direct.top + direct.bottom) / 2.0, detail.second, 0.15)
        }
    }

    @Test
    fun cameraPanAndZoomProduceExpectedNorthUpRectChanges() {
        val bounds = WebMercator.coordinateImageBounds(GeoPoint(51.5, -0.1), 7, 512)
        val centered = NorthUpRadarGeoreference.screenRect(
            bounds,
            GeoPoint(51.5, -0.1),
            7.0,
            1_000,
            800,
        )
        val zoomed = NorthUpRadarGeoreference.screenRect(
            bounds,
            GeoPoint(51.5, -0.1),
            8.0,
            1_000,
            800,
        )
        val panned = NorthUpRadarGeoreference.screenRect(
            bounds,
            GeoPoint(51.5, 0.4),
            7.0,
            1_000,
            800,
        )
        assertEquals(
            (centered.right - centered.left) * 2.0,
            zoomed.right - zoomed.left,
            1e-6,
        )
        assertTrue(panned.left < centered.left)
        assertEquals(centered.top, panned.top, 1e-6)
    }

    @Test
    fun regionalMotionWinsWhenDetailIsUnavailableAndInvalidMotionIsRejected() {
        val regional = physicalMotion(0.25, 0.0, RadarResolutionTier.REGIONAL)
        val detail = physicalMotion(1.0, 0.0, RadarResolutionTier.DETAIL)
        assertSame(regional, RadarMotionPolicy.preferred(regional, null))
        assertSame(regional, RadarMotionPolicy.preferred(regional, detail))
        assertSame(detail, RadarMotionPolicy.preferred(null, detail))
        assertNull(RadarMotionPolicy.preferred(null, null))
        assertEquals(1_200L, RadarTimeline.endOffsetSeconds(times, null))
        assertEquals(
            1_200L + RadarTimeline.FORECAST_HORIZON_SECONDS,
            RadarTimeline.endOffsetSeconds(times, regional),
        )
        assertFalse(
            RadarMotionPolicy.usable(
                regional.copy(dxWorldFractionPerMinute = 0.0, dyWorldFractionPerMinute = 0.0),
            ),
        )
        assertFalse(
            RadarMotionPolicy.usable(
                regional.copy(dxWorldFractionPerMinute = Double.NaN),
            ),
        )
    }

    @Test
    fun regionalSignalSucceedsWhenDetailAnalysisIsBlank() {
        val before = squareGrid()
        val after = translate(before, dx = 2, dy = -1)
        val regionalEstimate = RadarMotionEstimator.estimate(
            listOf(TimedIntensityGrid(0, before), TimedIntensityGrid(600, after)),
        )
        val blank = IntensityGrid(64, 64, FloatArray(64 * 64))
        val detailEstimate = RadarMotionEstimator.estimate(
            listOf(TimedIntensityGrid(0, blank), TimedIntensityGrid(600, blank)),
        )
        val regionalMotion = requireNotNull(regionalEstimate).let {
            PhysicalRadarMotion.fromAnalysisPixels(
                it,
                RadarResolutionTier.REGIONAL,
                analysisWidth = 64,
                analysisHeight = 64,
            )
        }

        assertNull(detailEstimate)
        assertTrue(RadarMotionPolicy.usable(regionalMotion))
        assertSame(regionalMotion, RadarMotionPolicy.preferred(regionalMotion, null))
    }

    @Test
    fun fiveThousandTimelineAndCameraStatesConflateToLatestAndStopAfterDispose() {
        val state = RadarOverlayStateMachine()
        var finalPlan: RadarOverlayFramePlan? = null
        repeat(5_000) { index ->
            val fraction = (index % 101) / 100.0
            val tier = RadarOverlayPlanner.activeTier(
                mapZoom = 4.0 + (index % 500) / 100.0,
                regionalAvailable = true,
                detailAvailable = true,
            )
            val current = RadarOverlayPlanner.plan(
                RadarTimelineBracket(0, 1, fraction, false, 0.0),
                tier,
                listOf(pairMotion, null),
                times,
                pairMotion,
            )
            finalPlan = current
            assertTrue(state.submit(current))
            assertValid(current)
        }
        assertEquals(finalPlan, state.consumeLatest())
        assertNull(state.consumeLatest())
        state.dispose()
        assertTrue(state.isDisposed)
        assertFalse(state.submit(requireNotNull(finalPlan)))
        assertNull(state.consumeLatest())
    }

    @Test
    fun teardownStopsOverlayAndDestroysMapBeforeOneRelease() {
        val calls = mutableListOf<String>()
        val teardown = RadarResourceTeardown(
            stopOverlay = { calls += "stop-overlay" },
            detachMap = { calls += "detach-map" },
            destroyMap = { calls += "destroy-map" },
            releaseSession = { calls += "release-session" },
        )
        teardown.close()
        teardown.close()
        assertEquals(
            listOf("stop-overlay", "detach-map", "destroy-map", "release-session"),
            calls,
        )
    }

    private fun physicalMotion(
        dxAnalysisPixelsPerMinute: Double,
        dyAnalysisPixelsPerMinute: Double,
        tier: RadarResolutionTier = RadarResolutionTier.REGIONAL,
    ): PhysicalRadarMotion = PhysicalRadarMotion.fromAnalysisPixels(
        MotionEstimate(dxAnalysisPixelsPerMinute, dyAnalysisPixelsPerMinute, 0.8, 3),
        tier,
        analysisWidth = 64,
        analysisHeight = 64,
    )

    private fun assertValid(plan: RadarOverlayFramePlan) {
        assertTrue(plan.firstIndex in times.indices)
        assertTrue(plan.secondIndex in times.indices)
        assertTrue(plan.firstAlpha in 0.0..1.0)
        assertTrue(plan.secondAlpha in 0.0..1.0)
        assertTrue(plan.firstDxWorldFraction.isFinite())
        assertTrue(plan.secondDyWorldFraction.isFinite())
    }

    private fun squareGrid(): IntensityGrid {
        val values = FloatArray(64 * 64)
        for (y in 22 until 34) for (x in 20 until 32) values[y * 64 + x] = 1f
        return IntensityGrid(64, 64, values)
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
