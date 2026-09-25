package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RegionalRadarAreas
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
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

    @Test fun maskIsOnlyAvailableForMeteoGroupUk() {
        assertNull(RadarCoverageMaskPolicy.mask(RadarProviderKind.OPEN_RAINVIEWER, uk, RadarMapStyle.DARK))
        assertNull(RadarCoverageMaskPolicy.mask(RadarProviderKind.EUMETNET_OPERA, uk, RadarMapStyle.DARK))
        assertNull(RadarCoverageMaskPolicy.mask(RadarProviderKind.METEOGROUP_REGIONAL, null, RadarMapStyle.DARK))
        RegionalRadarAreas.all.filterNot { it.id == "uk" }.forEach { area ->
            assertNull(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.METEOGROUP_REGIONAL, area, RadarMapStyle.DARK,
            ))
        }
        assertTrue(RadarCoverageMaskPolicy.mask(
            RadarProviderKind.METEOGROUP_REGIONAL, uk, RadarMapStyle.DARK,
        ) != null)
    }

    @Test fun versionedNominalCoverageHasSafeTopologyAndOrientation() {
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
        val mesh = RegionalProjectionMesh.build(uk)
        assertTrue(mesh.projectionAccurate)
        val footprint = mesh.perimeterClockwise().map { GeoPoint(it.latitude, it.longitude) }
        requireNotNull(MeteoNominalCoverage.forAreaId("uk")).forEach { polygon ->
            (listOf(polygon.exterior) + polygon.holes).flatten().forEach { point ->
                assertTrue(
                    "coverage point $point escaped the native projected footprint",
                    pointInRing(point, footprint) || distanceToRingDegrees(point, footprint) < 0.012,
                )
            }
        }
    }

    @Test fun everyMapStyleUsesRestrainedTranslucentUnknownScrim() {
        RadarMapStyle.entries.forEach { style ->
            val mask = requireNotNull(RadarCoverageMaskPolicy.mask(
                RadarProviderKind.METEOGROUP_REGIONAL, uk, style,
            ))
            assertTrue(mask.opacity in 0.20f..0.40f)
            assertEquals(0xff, mask.colorArgb ushr 24)
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
