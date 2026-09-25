package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.data.CoverageMaskDarknessPreference
import com.rainalarm.app.data.RadarProviderCapabilityResolver
import com.rainalarm.app.data.RadarProviderCoverageState
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.MeteoContinentalNominalCoverage
import com.rainalarm.app.domain.MeteoNominalCoverage
import com.rainalarm.app.domain.MeteoNominalCoveragePolygon
import com.rainalarm.app.domain.RegionalProjectionMesh
import com.rainalarm.app.domain.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class RadarCoverageMaskPolicyTest {
    private val uk = RegionalRadarAreas.all.single { it.id == "uk" }
    private val nl = RegionalRadarAreas.all.single { it.id == "nl" }
    private val de = RegionalRadarAreas.all.single { it.id == "de" }
    private val fr = RegionalRadarAreas.all.single { it.id == "fr" }
    private val ch = RegionalRadarAreas.all.single { it.id == "ch" }

    @Test fun maskIsAvailableForEveryVersionedMeteoGroupAreaOnly() {
        RegionalRadarAreas.all.forEach { area ->
            assertNull(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.OPEN_RAINVIEWER, area, RadarMapStyle.DARK,
            ))
            assertNull(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.EUMETNET_OPERA, area, RadarMapStyle.DARK,
            ))
            assertTrue(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.METEOGROUP_REGIONAL, area, RadarMapStyle.DARK,
            ) != null)
        }
        assertNull(RadarCoverageMaskPolicy.mask(RadarProviderKind.METEOGROUP_REGIONAL, null, RadarMapStyle.DARK))
    }

    @Test fun ukNominalCoverageRemainsVersionedWithSafeTopologyAndOrientation() {
        val mask = requireNotNull(RadarCoverageMaskPolicy.mask(
            RadarProviderKind.METEOGROUP_REGIONAL, uk, RadarMapStyle.SLATE,
        ))
        assertEquals("2026-09-25", MeteoNominalCoverage.GEOMETRY_VERSION)
        assertTrue(MeteoNominalCoverage.MAX_SIMPLIFICATION_ERROR_METRES <= 1_000.0)
        assertTrue(mask.coverage.isNotEmpty())
        mask.coverage.forEachIndexed { index, polygon ->
            assertRing("component $index exterior", polygon.exterior, counterClockwise = true)
            polygon.holes.forEachIndexed { holeIndex, hole ->
                assertRing("component $index hole $holeIndex", hole, counterClockwise = false)
            }
        }
        assertEquals(-WebMercator.MAX_LATITUDE, mask.outerRing.first().latitude, 0.0)
        assertEquals("UK geometry must remain unchanged", 189, mask.coverage.sumOf {
            it.exterior.size + it.holes.sumOf { hole -> hole.size }
        })
    }

    @Test fun netherlandsNominalCoverageContainsReportedPlaceAndHasCurvedBoundary() {
        assertContinentalCoverage(
            areaId = "nl",
            area = nl,
            siteCount = 8,
            representative = GeoPoint(51.4841, 5.8596),
            additionalInside = listOf(GeoPoint(52.3676, 4.9041)),
            outside = listOf(
                GeoPoint(55.5, 0.2),
                GeoPoint(55.5, 10.5),
                GeoPoint(49.0, 0.2),
                GeoPoint(49.0, 10.5),
            ),
        )
    }

    @Test fun germanyNominalCoverageUsesCurrentNationalNetworkArcs() {
        assertContinentalCoverage(
            areaId = "de",
            area = de,
            siteCount = 17,
            representative = GeoPoint(52.5200, 13.4050),
            additionalInside = listOf(GeoPoint(50.1109, 8.6821)),
            outside = listOf(GeoPoint(47.1, 2.1), GeoPoint(54.9, 2.2)),
        )
    }

    @Test fun franceNominalCoverageUsesCurrentActiveNationalNetworkArcs() {
        assertContinentalCoverage(
            areaId = "fr",
            area = fr,
            siteCount = 26,
            representative = GeoPoint(48.8566, 2.3522),
            additionalInside = listOf(GeoPoint(45.7640, 4.8357), GeoPoint(42.0396, 9.0129)),
            outside = listOf(GeoPoint(52.5, -8.5)),
        )
    }

    @Test fun switzerlandNominalCoverageUsesFiveSiteNationalNetworkArcs() {
        assertContinentalCoverage(
            areaId = "ch",
            area = ch,
            siteCount = 5,
            representative = GeoPoint(46.9480, 7.4474),
            additionalInside = listOf(GeoPoint(47.3769, 8.5417)),
            outside = listOf(GeoPoint(49.1, 3.0), GeoPoint(43.8, 12.2)),
        )
    }

    @Test fun northernRangeArcSeparatesFairIsleAndShetland() {
        val coverage = requireNotNull(MeteoNominalCoverage.forAreaId("uk"))
        assertTrue("Fair Isle must be inside nominal reach", coverage.contains(GeoPoint(59.53, -1.63)))
        assertFalse(
            "central Shetland Mainland must be outside nominal reach",
            coverage.contains(GeoPoint(60.32, -1.23)),
        )
        assertTrue("Great Baddow must be inside", coverage.contains(GeoPoint(51.729, 0.503)))
        assertTrue("Dublin must be inside", coverage.contains(GeoPoint(53.3498, -6.2603)))
        assertTrue("Stornoway must be inside", coverage.contains(GeoPoint(58.209, -6.386)))
        assertFalse("distant Atlantic must be outside", coverage.contains(GeoPoint(55.0, -15.0)))
    }

    @Test fun nominalGeometryIsClippedToAccurateNativeUkFootprint() {
        assertCoverageClippedToNativeFootprint("uk", uk)
    }

    @Test fun netherlandsGeometryIsClippedToAccurateNativeFootprint() {
        assertCoverageClippedToNativeFootprint("nl", nl)
    }

    @Test fun everyContinentalGeometryIsClippedToItsAccurateNativeFootprint() {
        listOf(de, fr, ch).forEach { area ->
            assertCoverageClippedToNativeFootprint(area.id, area)
        }
    }

    private fun assertContinentalCoverage(
        areaId: String,
        area: com.rainalarm.app.data.RegionalRadarArea,
        siteCount: Int,
        representative: GeoPoint,
        additionalInside: List<GeoPoint>,
        outside: List<GeoPoint>,
    ) {
        assertEquals(areaId, RegionalRadarAreas.forPoint(
            representative.latitude, representative.longitude,
        )?.id)
        assertEquals(
            RadarProviderCoverageState.COVERED,
            RadarProviderCapabilityResolver.capability(
                RadarProviderKind.METEOGROUP_REGIONAL, representative,
            ).state,
        )
        assertEquals(true, MeteoNominalCoverage.covers(areaId, representative))

        val geometry = requireNotNull(
            MeteoContinentalNominalCoverage.geometryForAreaId(areaId),
        )
        assertEquals(areaId, geometry.areaId)
        assertEquals("2026-09-25", geometry.geometryVersion)
        assertEquals(siteCount, geometry.siteCount)
        assertTrue(geometry.maxSimplificationErrorMetres <= 1_000.0)
        assertTrue(geometry.maxSimplificationErrorMetres > 0.0)

        val mask = requireNotNull(RadarCoverageMaskPolicy.mask(
            RadarProviderKind.METEOGROUP_REGIONAL, area, RadarMapStyle.DARK,
        ))
        assertTrue(mask.coverage.contains(representative))
        additionalInside.forEach { point ->
            assertTrue(mask.coverage.contains(point))
            assertEquals(true, MeteoNominalCoverage.covers(areaId, point))
        }
        outside.forEach { point ->
            assertFalse(mask.coverage.contains(point))
            assertEquals(false, MeteoNominalCoverage.covers(areaId, point))
        }
        assertTrue("$areaId site-range union must not collapse to a rectangle", mask.coverage.any { polygon ->
            polygon.exterior.size > 20 && polygon.exterior.zipWithNext().count { (first, second) ->
                abs(first.latitude - second.latitude) > 1e-4 &&
                    abs(first.longitude - second.longitude) > 1e-4
            } > 10
        })
        mask.coverage.forEachIndexed { index, polygon ->
            assertRing("$areaId component $index exterior", polygon.exterior, counterClockwise = true)
            polygon.holes.forEachIndexed { holeIndex, hole ->
                assertRing("$areaId component $index hole $holeIndex", hole, counterClockwise = false)
            }
        }
        assertEquals(-WebMercator.MAX_LATITUDE, mask.outerRing.first().latitude, 0.0)
    }

    private fun assertCoverageClippedToNativeFootprint(
        areaId: String,
        area: com.rainalarm.app.data.RegionalRadarArea,
    ) {
        val mesh = RegionalProjectionMesh.build(area)
        assertTrue(mesh.projectionAccurate)
        val footprint = mesh.perimeterClockwise().map { GeoPoint(it.latitude, it.longitude) }
        requireNotNull(MeteoNominalCoverage.forAreaId(areaId)).forEach { polygon ->
            (listOf(polygon.exterior) + polygon.holes).flatten().forEach { point ->
                assertTrue(
                    "coverage point $point escaped the native projected footprint",
                    pointInRing(point, footprint) || distanceToRingDegrees(point, footprint) < 0.012,
                )
            }
        }
    }

    @Test fun everyProviderMaskUsesTheSharedUserControlledStyleAwareScrim() {
        val expectedDefaults = mapOf(
            RadarMapStyle.DARK to (0xFF000000.toInt() to 0.45f),
            RadarMapStyle.SLATE to (0xFF101827.toInt() to 0.42f),
            RadarMapStyle.LIGHT to (0xFF24313A.toInt() to 0.40f),
        )
        val expectedMaximums = mapOf(
            RadarMapStyle.DARK to 0.90f,
            RadarMapStyle.SLATE to 0.84f,
            RadarMapStyle.LIGHT to 0.80f,
        )
        expectedDefaults.forEach { (style, palette) ->
            assertEquals(palette.first, RadarCoverageMaskPolicy.palette(style).first)
            assertEquals(palette.second, RadarCoverageMaskPolicy.palette(style).second, 0.0001f)
            assertEquals(0f, RadarCoverageMaskPolicy.palette(
                style, CoverageMaskDarknessPreference.MIN,
            ).second, 0f)
            assertEquals(expectedMaximums.getValue(style), RadarCoverageMaskPolicy.palette(
                style, CoverageMaskDarknessPreference.MAX,
            ).second, 0.0001f)
            val mask = requireNotNull(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.METEOGROUP_REGIONAL, uk, style,
                CoverageMaskDarknessPreference.DEFAULT,
            ))
            assertEquals(palette.first, mask.colorArgb)
            assertEquals(palette.second, mask.opacity, 0.0001f)
            assertEquals(expectedMaximums.getValue(style) * 0.5f, mask.opacity, 0.0001f)
            assertTrue(expectedMaximums.getValue(style) < 1f)
        }
    }

    @Test fun dynamicProviderMaskUsesBoundedRasterBandsOutsideLoadedRegionAndFailsOpenAtDateline() {
        val bounds = GeoQuad(
            GeoPoint(60.0, -15.0), GeoPoint(60.0, 10.0),
            GeoPoint(45.0, 10.0), GeoPoint(45.0, -15.0),
        )
        val bands = requireNotNull(RadarCoverageRasterBandPolicy.outsideBands(bounds))
        assertEquals(12, bands.size)
        assertEquals(bands.size, bands.indices.map(RadarCoverageRasterBandPolicy::sourceId).toSet().size)
        assertEquals(bands.size, bands.indices.map(RadarCoverageRasterBandPolicy::layerId).toSet().size)
        bands.forEach { band ->
            assertTrue(band.topLeft.latitude > band.bottomLeft.latitude)
            assertTrue(band.topRight.longitude > band.topLeft.longitude)
            assertTrue(band.topRight.longitude - band.topLeft.longitude <= 90.0)
            val centre = GeoPoint(
                (band.topLeft.latitude + band.bottomLeft.latitude) / 2.0,
                (band.topLeft.longitude + band.topRight.longitude) / 2.0,
            )
            assertFalse(centre.latitude in 45.0..60.0 && centre.longitude in -15.0..10.0)
        }
        assertNull(RadarCoverageRasterBandPolicy.outsideBands(
            GeoQuad(
                GeoPoint(20.0, 175.0), GeoPoint(20.0, 185.0),
                GeoPoint(10.0, 185.0), GeoPoint(10.0, 175.0),
            ),
        ))
    }

    private fun assertRing(label: String, ring: List<GeoPoint>, counterClockwise: Boolean) {
        assertTrue("$label must have at least four positions", ring.size >= 4)
        assertEquals("$label must be closed", ring.first(), ring.last())
        val signedDoubleArea = ring.zipWithNext().sumOf { (a, b) ->
            a.longitude * b.latitude - b.longitude * a.latitude
        }
        assertTrue(
            "$label has wrong orientation",
            if (counterClockwise) signedDoubleArea > 0.0 else signedDoubleArea < 0.0,
        )
        val segments = ring.zipWithNext()
        segments.forEachIndexed { firstIndex, first ->
            segments.forEachIndexed inner@{ secondIndex, second ->
                if (secondIndex <= firstIndex + 1) return@inner
                if (firstIndex == 0 && secondIndex == segments.lastIndex) return@inner
                assertFalse(
                    "$label segments $firstIndex and $secondIndex intersect",
                    properlyIntersect(first.first, first.second, second.first, second.second),
                )
            }
        }
    }

    private fun List<MeteoNominalCoveragePolygon>.contains(point: GeoPoint): Boolean = any { polygon ->
        pointInRing(point, polygon.exterior) && polygon.holes.none { pointInRing(point, it) }
    }

    private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
        var inside = false
        ring.zipWithNext().forEach { (first, second) ->
            if (distanceToSegmentDegrees(point, first, second) < 1e-8) return true
            val crosses = (first.latitude > point.latitude) != (second.latitude > point.latitude)
            if (crosses) {
                val longitude = (second.longitude - first.longitude) *
                    (point.latitude - first.latitude) / (second.latitude - first.latitude) + first.longitude
                if (point.longitude < longitude) inside = !inside
            }
        }
        return inside
    }

    private fun distanceToRingDegrees(point: GeoPoint, ring: List<GeoPoint>): Double =
        ring.zipWithNext().minOf { (first, second) -> distanceToSegmentDegrees(point, first, second) }

    private fun distanceToSegmentDegrees(point: GeoPoint, first: GeoPoint, second: GeoPoint): Double {
        val dx = second.longitude - first.longitude
        val dy = second.latitude - first.latitude
        if (abs(dx) + abs(dy) < 1e-12) {
            return hypot(point.longitude - first.longitude, point.latitude - first.latitude)
        }
        val fraction = (((point.longitude - first.longitude) * dx +
            (point.latitude - first.latitude) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(
            point.longitude - (first.longitude + fraction * dx),
            point.latitude - (first.latitude + fraction * dy),
        )
    }

    private fun properlyIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        fun side(p: GeoPoint, q: GeoPoint, r: GeoPoint): Double =
            (q.longitude - p.longitude) * (r.latitude - p.latitude) -
                (q.latitude - p.latitude) * (r.longitude - p.longitude)
        val abC = side(a, b, c)
        val abD = side(a, b, d)
        val cdA = side(c, d, a)
        val cdB = side(c, d, b)
        return abC * abD < 0.0 && cdA * cdB < 0.0
    }
}
