package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class SatelliteOverlayPolicyTest {
    private val london = SavedPlace("London", 51.5074, -0.1278)

    @Test fun `smallest place-owned region wins and camera is absent from cache context`() {
        assertEquals(SatelliteRegionId.BRITISH_ISLES, SatelliteRegionPolicy.select(london).id)
        assertEquals(SatelliteRegionId.EUROPE,
            SatelliteRegionPolicy.select(SavedPlace("Madrid", 40.4168, -3.7038)).id)
        assertEquals(SatelliteRegionId.NORTH_AMERICA_EAST,
            SatelliteRegionPolicy.select(SavedPlace("Denver overlap", 39.7392, -100.0)).id)
        assertEquals(SatelliteRegionId.NORTH_AMERICA_WEST,
            SatelliteRegionPolicy.select(SavedPlace("Seattle", 47.6062, -122.3321)).id)

        val londonContext = SatelliteCacheContext(SatelliteRegionPolicy.select(london))
        val leedsContext = SatelliteCacheContext(
            SatelliteRegionPolicy.select(SavedPlace("Leeds", 53.8008, -1.5491)),
        )
        assertEquals(londonContext, leedsContext)
        assertEquals(londonContext.identity, leedsContext.identity)
    }

    @Test fun `provider bounds are clipped to one buffered regional rectangle`() {
        val region = SatelliteRegionPolicy.select(london)
        val metadata = satellite(EumetProduct.LIGHTNING, 3_000L,
            west = -81.0, south = -77.0, east = 81.0, north = 77.0)
        val clipped = requireNotNull(SatelliteRegionPolicy.clip(metadata, region))
        assertEquals(region.requestBounds.west, clipped.west, 0.0)
        assertEquals(region.requestBounds.south, clipped.south, 0.0)
        assertEquals(region.requestBounds.east, clipped.east, 0.0)
        assertEquals(region.requestBounds.north, clipped.north, 0.0)
        assertTrue(region.requestBounds.west < region.selectionBounds.west)
        assertTrue(region.requestBounds.east > region.selectionBounds.east)
        assertTrue(region.requestBounds.south < region.selectionBounds.south)
        assertTrue(region.requestBounds.north > region.selectionBounds.north)
    }

    @Test fun `regional WMS request is exact bounded projected and pinned to EUMET frame`() {
        val region = SatelliteRegionPolicy.select(london)
        listOf(EumetProduct.CLOUD_TYPE, EumetProduct.FOG_LOW_CLOUD, EumetProduct.LIGHTNING)
            .forEach { product ->
                val metadata = requireNotNull(SatelliteRegionPolicy.clip(
                    satellite(product, Instant.parse("2026-09-24T01:20:00Z").epochSecond), region,
                ))
                val request = SatelliteRegionalImagePolicy.request(metadata)
                assertEquals(region.requestBounds, request.bounds)
                assertTrue(request.width in SatelliteRegionalImagePolicy.minimumDimension..
                    SatelliteRegionalImagePolicy.maximumDimension)
                assertTrue(request.height in SatelliteRegionalImagePolicy.minimumDimension..
                    SatelliteRegionalImagePolicy.maximumDimension)
                assertEquals(SatelliteRegionalImagePolicy.maximumDimension,
                    maxOf(request.width, request.height))
                val projected = request.projectedBbox.split(',').map(String::toDouble)
                val projectedAspect = (projected[2] - projected[0]) / (projected[3] - projected[1])
                assertEquals(projectedAspect, request.width.toDouble() / request.height, 0.01)
                val uri = URI(request.url)
                assertEquals("https", uri.scheme)
                assertEquals("view.eumetsat.int", uri.host)
                assertEquals("/geoserver/wms", uri.path)
                val query = uri.rawQuery.split('&').associate { part ->
                    val pieces = part.split('=', limit = 2)
                    URLDecoder.decode(pieces[0], StandardCharsets.UTF_8.name()) to
                        URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
                }
                assertEquals(product.layerName, query["layers"])
                assertEquals(Instant.ofEpochSecond(metadata.validEpochSeconds).toString(), query["time"])
                assertEquals(request.projectedBbox, query["bbox"])
                assertEquals(request.width.toString(), query["width"])
                assertEquals(request.height.toString(), query["height"])
                assertEquals("EPSG:3857", query["srs"])
                assertEquals("image/png", query["format"])
                assertEquals("true", query["transparent"])
            }

        val firstMetadata = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.CLOUD_TYPE, 2_000L), region,
        ))
        val first = SatelliteRegionalImagePolicy.request(firstMetadata)
        assertTrue(runCatching {
            SatelliteRegionalImagePolicy.requireAllowedRequest(
                first.url, firstMetadata, first.projectedBbox, first.width, first.height,
            )
        }.isSuccess)
        assertTrue(runCatching {
            SatelliteRegionalImagePolicy.requireAllowedRequest(
                first.url.replace("view.eumetsat.int", "example.invalid"),
                firstMetadata, first.projectedBbox, first.width, first.height,
            )
        }.isFailure)
        assertTrue(runCatching {
            SatelliteRegionalImagePolicy.requireAllowedRequest(
                first.url.replace("width=${first.width}", "width=${first.width - 1}"),
                firstMetadata, first.projectedBbox, first.width, first.height,
            )
        }.isFailure)
    }

    @Test fun `north American and local fallback regions remain bounded near dateline`() {
        assertEquals(SatelliteRegionId.NORTH_AMERICA_EAST,
            SatelliteRegionPolicy.select(SavedPlace("New York", 40.7, -74.0)).id)
        assertEquals(SatelliteRegionId.NORTH_AMERICA_WEST,
            SatelliteRegionPolicy.select(SavedPlace("San Francisco", 37.8, -122.4)).id)
        val dateline = SavedPlace("Dateline", 10.0, 179.5)
        val fallback = SatelliteRegionPolicy.select(dateline)
        assertEquals(SatelliteRegionId.LOCAL, fallback.id)
        assertTrue(fallback.requestBounds.east <= 180.0)
        assertTrue(fallback.requestBounds.west >= -180.0)
        assertTrue(fallback.requestBounds.contains(dateline.latitude, dateline.longitude))
    }

    @Test fun `frame window is finite cadence aligned and deduplicates forecast hold`() {
        val lightning = satellite(
            EumetProduct.LIGHTNING, 2_800L, available = 1_000L, cadence = 300L,
        )
        val frames = requireNotNull(SatelliteFrameWindowPolicy.frames(
            listOf(lightning), null, london, 1_150L, 7_000L,
        )[RadarMapLayer.LIGHTNING])
        assertEquals(listOf(1_000L, 1_300L, 1_600L, 1_900L, 2_200L, 2_500L, 2_800L),
            frames.map(EumetLayerMetadata::validEpochSeconds))
        assertEquals(frames.size, frames.map(EumetLayerMetadata::frameIdentity).distinct().size)
        assertEquals(1, frames.count { it.validEpochSeconds == lightning.latestEpochSeconds })
        assertTrue(frames.all { it.validEpochSeconds <= lightning.latestEpochSeconds })
    }

    @Test fun `cloud frame plan can cross product and never invents a forecast frame`() {
        val start = Instant.parse("2026-09-24T04:00:00Z").epochSecond
        val latest = Instant.parse("2026-09-24T20:00:00Z").epochSecond
        val catalog = listOf(
            satellite(EumetProduct.CLOUD_TYPE, latest, available = start, cadence = 600L),
            satellite(EumetProduct.FOG_LOW_CLOUD, latest, available = start, cadence = 600L),
        )
        val clouds = requireNotNull(SatelliteFrameWindowPolicy.frames(
            catalog, null, london, start, latest + 7_200L,
        )[RadarMapLayer.FOG])
        assertTrue(clouds.any { it.product == EumetProduct.CLOUD_TYPE })
        assertTrue(clouds.any { it.product == EumetProduct.FOG_LOW_CLOUD })
        assertTrue(clouds.all { it.validEpochSeconds <= latest })
        assertEquals(clouds.size, clouds.map(EumetLayerMetadata::frameIdentity).distinct().size)
    }

    @Test fun `full set remains hidden until every unique frame is ready`() {
        val context = SatelliteCacheContext(SatelliteRegionPolicy.select(london))
        val frames = listOf(
            satellite(EumetProduct.LIGHTNING, 1_000L),
            satellite(EumetProduct.LIGHTNING, 1_300L),
            satellite(EumetProduct.LIGHTNING, 1_300L),
        )
        val unique = SatelliteFullSetPolicy.uniqueFrames(frames, context)
        assertEquals(2, unique.size)
        val ready = mutableSetOf(SatelliteCacheIdentity.frame(unique[0], context))
        var progress = SatelliteFullSetPolicy.progress(unique, context, ready::contains)
        assertEquals(SatellitePlanProgress(1, 2), progress)
        assertFalse(progress.complete)
        assertNull(SatelliteFullSetPolicy.visibleFrame(unique[1], progress))

        ready += SatelliteCacheIdentity.frame(unique[1], context)
        progress = SatelliteFullSetPolicy.progress(unique, context, ready::contains)
        assertTrue(progress.complete)
        assertEquals(unique[1], SatelliteFullSetPolicy.visibleFrame(unique[1], progress))
    }

    @Test fun `full set progress reuses verified identities and exposes missing frames`() {
        val context = SatelliteCacheContext(SatelliteRegionPolicy.select(london))
        val frames = (0L..4L).map { satellite(EumetProduct.LIGHTNING, 1_000L + it * 300L) }
        val firstIdentity = SatelliteCacheIdentity.frame(frames[0], context)
        val ready = mutableSetOf(firstIdentity)
        assertEquals(SatellitePlanProgress(1, 5),
            SatelliteFullSetPolicy.progress(frames, context, ready::contains))
        val missing = SatelliteFullSetPolicy.uniqueFrames(frames, context).filter {
            SatelliteCacheIdentity.frame(it, context) !in ready
        }
        assertEquals(frames.drop(1), missing)
        ready.remove(firstIdentity)
        assertEquals(SatellitePlanProgress(0, 5),
            SatelliteFullSetPolicy.progress(frames, context, ready::contains))
    }

    @Test fun `refreshed plan reuses overlap and warms only its missing regional frame`() {
        val context = SatelliteCacheContext(SatelliteRegionPolicy.select(london))
        val oldPlan = (0L..1L).map {
            satellite(EumetProduct.LIGHTNING, 1_000L + it * 300L)
        }
        val refreshedPlan = oldPlan + satellite(EumetProduct.LIGHTNING, 1_600L)
        val ready = oldPlan.mapTo(mutableSetOf()) { SatelliteCacheIdentity.frame(it, context) }

        val progress = SatelliteFullSetPolicy.progress(refreshedPlan, context, ready::contains)
        assertEquals(SatellitePlanProgress(2, 3), progress)
        assertFalse(progress.complete)
        assertNull(SatelliteFullSetPolicy.visibleFrame(refreshedPlan.last(), progress))
        assertEquals(listOf(refreshedPlan.last()), refreshedPlan.filter {
            SatelliteCacheIdentity.frame(it, context) !in ready
        })
    }

    @Test fun `cloud and lightning preparation ledgers remain independent`() {
        val context = SatelliteCacheContext(SatelliteRegionPolicy.select(london))
        val cloud = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.CLOUD_TYPE, 2_000L), context.region,
        ))
        val lightning = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.LIGHTNING, 2_000L), context.region,
        ))
        val ready = setOf(SatelliteCacheIdentity.frame(cloud, context))
        assertTrue(SatelliteFullSetPolicy.progress(listOf(cloud), context, ready::contains).complete)
        assertFalse(SatelliteFullSetPolicy.progress(
            listOf(lightning), context, ready::contains,
        ).complete)
    }

    @Test fun `cache identity is stable across camera and place in region but distinct by frame`() {
        val britishIsles = SatelliteRegionPolicy.select(london)
        val londonContext = SatelliteCacheContext(britishIsles)
        val leedsContext = SatelliteCacheContext(
            SatelliteRegionPolicy.select(SavedPlace("Leeds", 53.8008, -1.5491)),
        )
        val madridContext = SatelliteCacheContext(
            SatelliteRegionPolicy.select(SavedPlace("Madrid", 40.4168, -3.7038)),
        )
        val frame = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.CLOUD_TYPE, 2_000L), britishIsles,
        ))
        assertEquals(SatelliteCacheIdentity.frame(frame, londonContext),
            SatelliteCacheIdentity.frame(frame, leedsContext))
        assertNotEquals(SatelliteCacheIdentity.frame(frame, londonContext),
            SatelliteCacheIdentity.frame(frame, madridContext))
        val later = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.CLOUD_TYPE, 2_600L), britishIsles,
        ))
        val otherProduct = requireNotNull(SatelliteRegionPolicy.clip(
            satellite(EumetProduct.FOG_LOW_CLOUD, 2_000L), britishIsles,
        ))
        assertNotEquals(SatelliteCacheIdentity.frame(frame, londonContext),
            SatelliteCacheIdentity.frame(later, londonContext))
        assertNotEquals(SatelliteCacheIdentity.frame(frame, londonContext),
            SatelliteCacheIdentity.frame(otherProduct, londonContext))
    }

    @Test fun `revealed source keeps predecessor until later matching fully rendered frame`() {
        assertFalse(SatelliteHandoffPolicy.mayRetire(
            expectedGeneration = 12L, activeGeneration = 12L,
            revealedAtRenderSequence = 40L, currentRenderSequence = 40L,
            fullyRendered = true,
        ))
        assertFalse(SatelliteHandoffPolicy.mayRetire(
            expectedGeneration = 12L, activeGeneration = 11L,
            revealedAtRenderSequence = 40L, currentRenderSequence = 41L,
            fullyRendered = true,
        ))
        assertFalse(SatelliteHandoffPolicy.mayRetire(
            expectedGeneration = 12L, activeGeneration = 12L,
            revealedAtRenderSequence = 40L, currentRenderSequence = 41L,
            fullyRendered = false,
        ))
        assertTrue(SatelliteHandoffPolicy.mayRetire(
            expectedGeneration = 12L, activeGeneration = 12L,
            revealedAtRenderSequence = 40L, currentRenderSequence = 41L,
            fullyRendered = true,
        ))
    }

    private fun satellite(
        product: EumetProduct,
        latest: Long,
        west: Double = -81.0,
        south: Double = -77.0,
        east: Double = 81.0,
        north: Double = 77.0,
        available: Long = latest,
        cadence: Long = product.nominalCadenceSeconds,
    ) = EumetLayerMetadata(
        product.conceptualLayer, latest, west, south, east, north,
        product, available, latest, cadence,
    )
}
