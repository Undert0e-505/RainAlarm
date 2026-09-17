package com.rainalarm.app.data

import com.rainalarm.app.domain.RadarTimeline
import com.rainalarm.app.domain.RegionalProjectionMesh
import com.rainalarm.app.domain.RegionalProjection
import com.rainalarm.app.domain.RAIN_INTENSITY_THRESHOLD
import com.rainalarm.app.domain.RegionalPointPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarProviderTest {
    @Test
    fun regionalPointSamplerStaysDryWhenWetNeighboursDoNotCoverMarker() {
        val nearby = FloatArray(25).apply { this[5] = 0.5f; this[6] = 0.5f; this[10] = 0.5f }
        val patch = RegionalPointPatch(5, 5, 0, 0, 5, 5, 2.5, 2.5, nearby)
        assertEquals(0f, patch.sample(), 0f)
        assertTrue(nearby.count { it >= RAIN_INTENSITY_THRESHOLD } >= 3)
        nearby[12] = 0.5f
        assertEquals(0.5f, patch.sample(), 0f)
        val adjacent = RegionalPointPatch(5, 5, 0, 0, 5, 5, 2.0, 2.5, nearby)
        assertEquals(0.3f, adjacent.sample(), 0.0001f)
    }

    @Test
    fun manifestParsesExactTimesForecastBoundaryAndEmptyFinalVelocity() {
        val xml = """
            <MapParameterProductionFeed><ValidFilenames>
              <Time imageFilename="observation/rad_202609151130.jpg" velocityFilename="observation/vel_202609151130.jpg" dtg="2026-09-15T11:30:00+0000" mtime="2026-09-15T11:43:37+0000" forecast="0" />
              <Time imageFilename="observation/rad_202609151135.jpg" velocityFilename="observation/vel_202609151135.jpg" dtg="2026-09-15T11:35:00+0000" mtime="2026-09-15T11:43:38+0000" forecast="0" />
              <Time imageFilename="forecast_202609151135/for_202609151140.jpg" velocityFilename="forecast_202609151135/vel_202609151140.jpg" dtg="2026-09-15T11:40:00+0000" mtime="2026-09-15T11:43:38+0000" forecast="1" />
              <Time imageFilename="forecast_202609151135/for_202609151235.jpg" velocityFilename="" dtg="2026-09-15T12:35:00+0000" mtime="2026-09-15T11:43:42+0000" forecast="1" />
            </ValidFilenames></MapParameterProductionFeed>
        """.trimIndent()
        val frames = RegionalManifestParser.parse(xml)
        assertEquals(4, frames.size)
        assertFalse(frames[1].forecast)
        assertTrue(frames[2].forecast)
        assertEquals(300L, frames[2].timestamp - frames[1].timestamp)
        assertNull(frames.last().velocityFilename)
    }

    @Test
    fun pointOnlyAnalysisFetchesEveryForecastBracketVelocityWithoutRetainingOldHistory() {
        val loader = MeteoGroupRadarSessionLoader()
        val latestObservation = 1_300L
        fun frame(time: Long, forecast: Boolean) = RegionalManifestFrame(
            "observation/rad.jpg", "observation/vel.jpg", time, time, forecast,
        )
        assertFalse(loader.shouldLoadRegionalVelocity(RadarLoadMode.ALERT_ANALYSIS, frame(1_000, false), latestObservation))
        assertTrue(loader.shouldLoadRegionalVelocity(RadarLoadMode.ALERT_ANALYSIS, frame(1_300, false), latestObservation))
        assertTrue(loader.shouldLoadRegionalVelocity(RadarLoadMode.ALERT_ANALYSIS, frame(1_600, true), latestObservation))
        assertTrue(loader.shouldLoadRegionalVelocity(RadarLoadMode.ALERT_ANALYSIS, frame(1_900, true), latestObservation))
        assertTrue(loader.shouldLoadRegionalVelocity(RadarLoadMode.SCREEN_TWO_TIER, frame(1_000, false), latestObservation))
    }

    @Test(expected = IllegalArgumentException::class)
    fun manifestRejectsTraversal() {
        RegionalManifestParser.parse(
            """<Time imageFilename="observation/../secret.jpg" velocityFilename="" dtg="2026-09-15T11:30:00+0000" mtime="2026-09-15T11:30:00+0000" forecast="0" />
               <Time imageFilename="observation/rad_202609151135.jpg" velocityFilename="" dtg="2026-09-15T11:35:00+0000" mtime="2026-09-15T11:35:00+0000" forecast="0" />""",
        )
    }

    @Test
    fun areaSelectionUsesCentralCoverageRatherThanOverlappingBoundingBoxSize() {
        val representative = listOf(
            Triple("Great Baddow, Essex", 51.70 to 0.50, "uk"),
            Triple("east UK, Great Yarmouth", 52.61 to 1.73, "uk"),
            Triple("London", 51.50 to -0.10, "uk"),
            Triple("Paris", 48.86 to 2.35, "fr"),
            Triple("northern France, Lille", 50.63 to 3.06, "fr"),
            Triple("Amsterdam", 52.37 to 4.90, "nl"),
            Triple("Brussels", 50.85 to 4.35, "nl"),
            Triple("western Germany, Cologne", 50.94 to 6.96, "de"),
            Triple("Frankfurt", 50.11 to 8.68, "de"),
            Triple("Bern", 46.95 to 7.45, "ch"),
        )
        representative.forEach { (name, point, expected) ->
            val selected = RegionalRadarAreas.forPoint(point.first, point.second)
            assertEquals(name, expected, selected?.id)
            assertNotNull("$name should lie inside its chosen raster", RegionalProjection.pixelFor(
                requireNotNull(selected), point.first, point.second,
            ))
        }
        assertEquals("uk", RegionalRadarAreas.forPoint(51.70, 0.50)?.id)
        assertNull(RegionalRadarAreas.forPoint(35.0, 139.0))
    }

    @Test
    fun providerPreferenceDefaultsSafely() {
        assertEquals(RadarProviderKind.METEOGROUP_REGIONAL, RadarProviderPreference.decode(null))
        assertEquals(RadarProviderKind.METEOGROUP_REGIONAL, RadarProviderPreference.decode("removed-provider"))
        assertEquals(RadarProviderKind.OPEN_RAINVIEWER, RadarProviderPreference.decode("OPEN_RAINVIEWER"))
    }

    @Test
    fun velocityDecodingMatchesRecoveredShaderRange() {
        assertEquals(-1f, VelocityTextureCodec.decode(0, 255).first, 0f)
        assertEquals(1f, VelocityTextureCodec.decode(0, 255).second, 0f)
        val neutral = VelocityTextureCodec.decode(128, 128)
        assertTrue(kotlin.math.abs(neutral.first) < 0.005f)
        assertTrue(kotlin.math.abs(neutral.second) < 0.005f)
    }

    @Test
    fun providerTimelineInterpolatesContinuouslyAcrossForecastBoundary() {
        val times = listOf(1_000L, 1_300L, 1_600L, 1_900L)
        val flags = listOf(false, false, true, true)
        val midpoint = RadarTimeline.bracket(times, flags, 1_450.0)
        assertEquals(1, midpoint.firstIndex)
        assertEquals(2, midpoint.secondIndex)
        assertEquals(0.5, midpoint.fraction, 0.0)
        assertTrue(midpoint.isForecast)
        assertEquals(2.5, midpoint.forecastMinutes, 0.0)
    }

    @Test
    fun everyRegionBuildsCachedProjectionCorrectTessellatedMesh() {
        RegionalRadarAreas.all.forEach { area ->
            val mesh = RegionalProjectionMesh.build(area)
            assertTrue("${area.id} should use its native projection", mesh.projectionAccurate)
            assertTrue(mesh.vertices.size > 500)
            assertEquals((mesh.columns - 1) * (mesh.rows - 1) * 6, mesh.indices.size)
            assertTrue(mesh.vertices.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
            assertEquals(0f, mesh.vertices.first().v, 0f)
            assertEquals(1f, mesh.vertices.last().v, 0f)
            val centerPixel = RegionalProjection.pixelFor(
                area,
                (area.north + area.south) / 2.0,
                (area.west + area.east) / 2.0,
            )
            assertTrue("${area.id} center should map inside its raster", centerPixel != null)
        }
    }
}
