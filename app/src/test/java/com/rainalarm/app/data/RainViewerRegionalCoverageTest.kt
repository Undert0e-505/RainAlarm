package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.RadarOverlayPlanner
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainViewerRegionalCoverageTest {
    @Test
    fun ukSelectionUsesExactRegionalBoundsAndTwoZ4Windows() {
        val area = RegionalRadarAreas.all.single { it.id == "uk" }
        val plan = RainViewerRasterPlanner.forTier(
            SavedPlace("Great Baddow", 51.717, 0.469),
            RadarResolutionTier.REGIONAL,
        )

        assertEquals("uk", plan.regionalAreaId)
        assertEquals(area.bounds, plan.targetBounds)
        assertEquals(4, plan.zoom)
        assertEquals(512, plan.imageSize)
        assertEquals(2, plan.segments.size)
        assertTrue(contains(plan, GeoPoint(55.8642, -4.2518))) // Glasgow
        assertTrue(contains(plan, GeoPoint(56.8165, -5.1121))) // Fort William
        assertTrue(plan.outputWidth in 460..475)
        assertTrue(plan.outputHeight in 530..545)
        assertCoveredBySource(plan, GeoPoint(area.north, area.west))
        assertCoveredBySource(plan, GeoPoint(area.north, area.east))
        assertCoveredBySource(plan, GeoPoint(area.south, area.west))
        assertCoveredBySource(plan, GeoPoint(area.south, area.east))
    }

    @Test
    fun everyKnownAreaUsesAtMostTwoUsefulResolutionWindows() {
        val expectedZooms = mapOf("uk" to 4, "de" to 4, "nl" to 5, "ch" to 5, "fr" to 4)
        RegionalRadarAreas.all.forEach { area ->
            val plan = RainViewerRasterPlanner.forRegionalArea(area)
            assertEquals(area.bounds, plan.targetBounds)
            assertEquals(expectedZooms.getValue(area.id), plan.zoom)
            assertTrue(plan.segments.size in 1..2)
            assertTrue(plan.outputWidth > 0 && plan.outputHeight > 0)
        }
    }

    @Test
    fun detailRemainsSelectedPlaceCentredAndLocal() {
        val place = SavedPlace("Great Baddow", 51.717, 0.469)
        val plan = RainViewerRasterPlanner.forTier(place, RadarResolutionTier.DETAIL)

        assertEquals(null, plan.regionalAreaId)
        assertEquals(7, plan.zoom)
        assertEquals(1, plan.segments.size)
        assertTrue(contains(plan, GeoPoint(place.latitude, place.longitude)))
        assertFalse(contains(plan, GeoPoint(55.8642, -4.2518)))
        assertEquals(512, plan.outputWidth)
        assertEquals(512, plan.outputHeight)
    }

    @Test
    fun outsideKnownAreasUsesHonestSelectedPlaceRegionalFallback() {
        val place = SavedPlace("Tokyo", 35.6762, 139.6503)
        val plan = RainViewerRasterPlanner.forTier(place, RadarResolutionTier.REGIONAL)

        assertEquals(null, plan.regionalAreaId)
        assertEquals(RadarResolutionTier.REGIONAL.zoom, plan.zoom)
        assertEquals(1, plan.segments.size)
        assertTrue(contains(plan, GeoPoint(place.latitude, place.longitude)))
        val span = WebMercator.worldFractionSpan(plan.targetBounds)
        assertEquals(1.0 / 32.0, span.width, 1e-10)
        assertEquals(1.0 / 32.0, span.height, 1e-10)
    }

    @Test
    fun regionalUrlUsesPlannedCenterZoomAndRetinaSize() {
        val plan = RainViewerRasterPlanner.forRegionalArea(
            RegionalRadarAreas.all.single { it.id == "uk" },
        )
        val first = plan.segments.first()
        val url = buildCoordinateRadarUrl(
            "https://tilecache.rainviewer.com",
            RainViewerFrame(1000, "/v2/radar/example"),
            first.center,
            plan.zoom,
            plan.imageSize,
        )

        assertTrue(url.contains("/512/4/${first.center.latitude}/${first.center.longitude}/2/1_0.png"))
    }

    @Test
    fun mapKeepsCompleteRegionalRasterAtEveryZoom() {
        assertEquals(RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(6.349, regionalAvailable = true, detailAvailable = true))
        assertEquals(RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(6.35, regionalAvailable = true, detailAvailable = true))
        assertEquals(RadarResolutionTier.REGIONAL,
            RadarOverlayPlanner.activeTier(18.0, regionalAvailable = true, detailAvailable = true))
        assertEquals(RadarResolutionTier.DETAIL,
            RadarOverlayPlanner.activeTier(18.0, regionalAvailable = false, detailAvailable = true))
    }

    private fun contains(plan: RainViewerRasterPlan, point: GeoPoint): Boolean =
        point.latitude in plan.targetBounds.bottomLeft.latitude..plan.targetBounds.topLeft.latitude &&
            point.longitude in plan.targetBounds.topLeft.longitude..plan.targetBounds.topRight.longitude

    private fun assertCoveredBySource(plan: RainViewerRasterPlan, point: GeoPoint) {
        assertTrue(plan.segments.any { segment ->
            point.latitude in segment.sourceBounds.bottomLeft.latitude..segment.sourceBounds.topLeft.latitude &&
                point.longitude in segment.sourceBounds.topLeft.longitude..segment.sourceBounds.topRight.longitude
        })
    }
}
