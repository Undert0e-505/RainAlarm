package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.OpenRadarColorScale
import com.rainalarm.app.domain.WebMercator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream

class OperaCogTest {
    @Test fun sharedRangeCacheSingleFlightsAndReusesImmutableCogRanges() = runBlocking {
        val calls = AtomicInteger()
        val endpoint = object : OperaRangeEndpoint {
            override val cacheNamespace = "test-${UUID.randomUUID()}"
            override suspend fun range(
                key: String,
                firstByte: Long,
                lastByteInclusive: Long,
            ): OperaRangeResult {
                calls.incrementAndGet()
                delay(75)
                return OperaRangeResult(ByteArray((lastByteInclusive - firstByte + 1L).toInt()) { 7 }, 999L)
            }
        }

        val concurrent = coroutineScope {
            List(8) { async { OperaSharedSourceCache.range(endpoint, "frame-a", 100L, 199L) } }.awaitAll()
        }
        assertEquals(1, calls.get())
        assertEquals(1, concurrent.sumOf { it.networkRequests })
        assertEquals(100L, concurrent.sumOf { it.networkBytes })
        assertTrue(concurrent.all { it.result?.bytes?.size == 100 })

        val cached = OperaSharedSourceCache.range(endpoint, "frame-a", 100L, 199L)
        assertEquals(0, cached.networkRequests)
        assertEquals(0L, cached.networkBytes)
        assertEquals(1, calls.get())

        val nextObject = OperaSharedSourceCache.range(endpoint, "frame-b", 100L, 199L)
        assertEquals(1, nextObject.networkRequests)
        assertEquals(2, calls.get())
    }

    @Test fun sharedSourceBoundsNetworkConcurrencyAcrossIndependentConsumers() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val endpoint = object : OperaRangeEndpoint {
            override val cacheNamespace = "bounded-${UUID.randomUUID()}"
            override suspend fun range(
                key: String,
                firstByte: Long,
                lastByteInclusive: Long,
            ): OperaRangeResult {
                val nowActive = active.incrementAndGet()
                maximum.getAndUpdate { maxOf(it, nowActive) }
                return try {
                    delay(40)
                    OperaRangeResult(byteArrayOf(1), 1_000L)
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        coroutineScope {
            List(OperaSharedSourceCache.MAX_SHARED_NETWORK_CONCURRENCY * 3) { index ->
                async { OperaSharedSourceCache.range(endpoint, "frame-$index", 0L, 0L) }
            }.awaitAll()
        }
        assertTrue(maximum.get() <= OperaSharedSourceCache.MAX_SHARED_NETWORK_CONCURRENCY)
        assertEquals(OperaSharedSourceCache.MAX_SHARED_NETWORK_CONCURRENCY, maximum.get())
    }

    @Test fun latestProbeSelectsNewestAvailableNotFastestCompletion() = runBlocking {
        val candidates = OperaFrameKeyPolicy.latestCandidates(Instant.parse("2026-09-24T21:24:00Z"))
            .take(3)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val result = OperaLatestProbePolicy.newest(
            candidates = candidates,
            attempt = { candidate ->
                val nowActive = active.incrementAndGet()
                maximum.getAndUpdate { maxOf(it, nowActive) }
                try {
                    when (candidate) {
                        candidates[0] -> { delay(40); null }
                        candidates[1] -> { delay(80); "newest-available" }
                        else -> { delay(5); "older-but-fastest" }
                    }
                } finally {
                    active.decrementAndGet()
                }
            },
            available = { it != null },
        )
        assertEquals("newest-available", result.selected)
        assertEquals(3, maximum.get())
    }

    @Test fun latestProbeUsesSecondBoundedBatchOnlyWhenFirstIsMissing() = runBlocking {
        val candidates = OperaFrameKeyPolicy.latestCandidates(Instant.parse("2026-09-24T21:24:00Z"))
        val called = mutableListOf<OperaFrameKey>()
        val result = OperaLatestProbePolicy.newest(
            candidates = candidates,
            attempt = { candidate ->
                synchronized(called) { called += candidate }
                candidate.takeIf { it == candidates[3] }
            },
            available = { it != null },
        )
        assertEquals(candidates[3], result.selected)
        assertEquals(candidates.take(6).toSet(), called.toSet())
    }

    @Test fun rangePlannerCoalescesOnlyBoundedDenseCogSlabs() {
        val base = OperaCogParser.parse(tiffFixture(OperaTiffEndian.LITTLE), 300_000L)
        val denseOffsets = base.tileOffsets.copyOf().apply {
            this[2] = 10_000L; this[9] = 10_100L; this[17] = 10_300L
        }
        val denseCounts = base.tileByteCounts.copyOf().apply {
            this[2] = 100L; this[9] = 200L; this[17] = 100L
        }
        assertEquals(1, OperaRangePlanner.groups(
            base.copy(tileOffsets = denseOffsets, tileByteCounts = denseCounts),
            listOf(2, 9, 17),
        ).size)

        val sparseOffsets = base.tileOffsets.copyOf().apply {
            this[2] = 10_000L; this[9] = 50_000L; this[17] = 100_000L
        }
        assertEquals(3, OperaRangePlanner.groups(
            base.copy(tileOffsets = sparseOffsets, tileByteCounts = denseCounts),
            listOf(2, 9, 17),
        ).size)
    }

    @Test fun deterministicKeysRespectPublicationLagCadenceAndUtcMidnight() {
        val candidates = OperaFrameKeyPolicy.latestCandidates(Instant.parse("2026-09-25T00:02:00Z"))
        assertEquals("2026/09/24/OPERA/COMP/OPERA@20260924T2355@0@DBZH.tiff", candidates.first().key)
        assertEquals(300L, candidates[0].validityEpochSeconds - candidates[1].validityEpochSeconds)
        val history = OperaFrameKeyPolicy.history(candidates.first().validityEpochSeconds, 13)
        assertEquals(13, history.size)
        assertEquals(3_600L, history.last().validityEpochSeconds - history.first().validityEpochSeconds)
    }

    @Test fun classicTiffMetadataParsesBothEndianPathsAndExactLaeaTags() {
        for (endian in OperaTiffEndian.entries) {
            val fixture = tiffFixture(endian)
            val metadata = OperaCogParser.parse(fixture, 300_000L)
            assertEquals(endian, metadata.endian)
            assertEquals(3_800, metadata.width)
            assertEquals(4_400, metadata.height)
            assertEquals(72, metadata.tileOffsets.size)
            assertEquals(1_000.0, metadata.pixelWidthMetres, 0.0)
            assertEquals(55.0, metadata.centreLatitude, 0.0)
            assertEquals(10.0, metadata.centreLongitude, 0.0)
            assertEquals(1_950_000.0, metadata.falseEasting, 0.0)
            assertEquals(-2_100_000.0, metadata.falseNorthing, 0.0)
            assertTrue(metadata.projection.contains("+proj=laea"))
        }
    }

    @Test fun deflateTileReadsFloat32ContiguousBandsAndDistinguishesDryFromFill() {
        for (endian in OperaTiffEndian.entries) {
            val metadata = OperaCogParser.parse(tiffFixture(endian), 300_000L)
            val raw = ByteBuffer.allocate(512 * 512 * 2 * 4).order(endian.byteOrder)
            repeat(512 * 512) { index ->
                raw.putFloat(when (index) { 0 -> 15.5f; 1 -> Float.NaN; else -> -9_999_000f })
                raw.putFloat(if (index < 2) 0.9f else -9_999_000f)
            }
            val compressed = ByteArrayOutputStream().also { output ->
                DeflaterOutputStream(output).use { it.write(raw.array()) }
            }.toByteArray()
            val decoded = OperaTileDecoder.decode(compressed, metadata, 0)
            assertEquals(15.5f, decoded.reflectivity(0), 0f)
            assertTrue(decoded.reflectivity(1).isNaN())
            assertTrue(OperaTileDecoder.isCovered(decoded.reflectivity(0), metadata.noData))
            assertTrue(OperaTileDecoder.isCovered(decoded.reflectivity(1), metadata.noData))
            assertFalse(OperaTileDecoder.isCovered(decoded.reflectivity(2), metadata.noData))
            assertEquals(0f, OpenRadarColorScale.sharedIntensityForDbz(
                OperaTileDecoder.reflectivityOrDry(decoded.reflectivity(1))), 0f)
        }
    }

    @Test fun projectionAndSamplingPlansUseExactRegionalBoundsAndLocalNativeDetail() {
        val metadata = OperaCogParser.parse(tiffFixture(OperaTiffEndian.LITTLE), 300_000L)
        val projection = OperaProjection(metadata)
        val centre = projection.sourcePixel(GeoPoint(55.0, 10.0))
        assertEquals(1_950f, centre.x, 0.02f)
        assertEquals(2_100f, centre.y, 0.02f)
        // Independent PROJ reference values guard against a transform which only happens to
        // agree at the LAEA natural origin.
        val glasgow = projection.sourcePixel(GeoPoint(55.86, -4.25))
        assertEquals(1_064.615f, glasgow.x, 0.05f)
        assertEquals(1_913.560f, glasgow.y, 0.05f)
        val essex = projection.sourcePixel(GeoPoint(51.70, 0.50))
        assertEquals(1_295.176f, essex.x, 0.05f)
        assertEquals(2_423.205f, essex.y, 0.05f)
        val fortWilliam = projection.sourcePixel(GeoPoint(56.8198, -5.1052))
        assertEquals(1_035.596f, fortWilliam.x, 0.05f)
        assertEquals(1_797.748f, fortWilliam.y, 0.05f)
        val dover = projection.sourcePixel(GeoPoint(51.1279, 1.3134))
        assertEquals(1_343.292f, dover.x, 0.05f)
        assertEquals(2_493.635f, dover.y, 0.05f)
        // OPERA 2026-09-25 00:40Z ODIM outer-corner metadata, independently transformed by
        // GDAL/PROJ. These guard row orientation and PixelIsArea handling far from the origin.
        listOf(
            Triple(GeoPoint(67.0228327624372, -39.5357864125034), 0f, 0f),
            Triple(GeoPoint(67.6210371071631, 57.8119647501499), 3_800f, 0f),
            Triple(GeoPoint(31.7462153182675, -10.4345768386404), 0f, 4_400f),
            Triple(GeoPoint(31.987650276733, 29.421038635578), 3_800f, 4_400f),
        ).forEach { (point, x, y) ->
            val pixel = projection.sourcePixel(point)
            assertEquals(x, pixel.x, 0.08f)
            assertEquals(y, pixel.y, 0.08f)
        }
        val area = RegionalRadarAreas.all.single { it.id == "uk" }
        val regional = OperaSamplingPlanner.regional(metadata, area)
        assertEquals(area.bounds, regional.bounds)
        assertEquals(area.rasterWidth, regional.width)
        assertEquals(area.rasterHeight, regional.height)
        assertArrayEquals(
            intArrayOf(17, 18, 24, 25, 26, 27, 32, 33, 34, 35, 40, 41, 42, 43, 50, 51),
            regional.requiredTiles,
        )
        val selected = SavedPlace("Great Baddow", 51.70, 0.50, id = "test")
        val detail = OperaSamplingPlanner.detail(metadata, selected)
        val expectedDetailBounds = WebMercator.coordinateImageBounds(
            GeoPoint(selected.latitude, selected.longitude), detail.tier.zoom, 512,
        )
        assertEquals(expectedDetailBounds, detail.bounds)
        assertEquals(194, detail.width)
        assertEquals(194, detail.height)
        assertTrue(detail.bounds.topLeft.latitude > 51.70 && detail.bounds.bottomRight.latitude < 51.70)
        assertTrue(detail.bounds.topLeft.longitude < 0.50 && detail.bounds.bottomRight.longitude > 0.50)
        val selectedWorld = WebMercator.worldFraction(GeoPoint(selected.latitude, selected.longitude))
        val detailTopLeft = WebMercator.worldFraction(detail.bounds.topLeft)
        val detailBottomRight = WebMercator.worldFraction(detail.bounds.bottomRight)
        assertEquals(selectedWorld.first, (detailTopLeft.first + detailBottomRight.first) / 2.0, 1e-12)
        assertEquals(selectedWorld.second, (detailTopLeft.second + detailBottomRight.second) / 2.0, 1e-12)
        assertTrue(detail.requiredTiles.size < regional.requiredTiles.size)
    }

    private fun tiffFixture(endian: OperaTiffEndian): ByteArray {
        val order = endian.byteOrder
        val bytes = ByteArray(8 * 1024)
        val buffer = ByteBuffer.wrap(bytes).order(order)
        buffer.put(if (endian == OperaTiffEndian.LITTLE) 'I'.code.toByte() else 'M'.code.toByte())
        buffer.put(if (endian == OperaTiffEndian.LITTLE) 'I'.code.toByte() else 'M'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(8)
        data class Tag(val id: Int, val type: Int, val values: Any)
        val tileOffsets = LongArray(72) { 100_000L + it * 1_000L }
        val tileCounts = LongArray(72) { 500L }
        val geoDoubles = doubleArrayOf(55.0, 10.0, 1_950_000.0, -2_100_000.0,
            298.257223563, 6_378_137.0, 0.0)
        val geoKeys = intArrayOf(
            1, 1, 0, 11,
            1024, 0, 1, 1,
            1025, 0, 1, 1,
            2057, 34736, 1, 5,
            2059, 34736, 1, 4,
            3075, 0, 1, 10,
            3076, 0, 1, 9001,
            3082, 34736, 1, 2,
            3083, 34736, 1, 3,
            3088, 34736, 1, 1,
            3089, 34736, 1, 0,
            2054, 0, 1, 9102,
        )
        val tags = listOf(
            Tag(256, 4, longArrayOf(3_800)), Tag(257, 4, longArrayOf(4_400)),
            Tag(258, 3, intArrayOf(32, 32)), Tag(259, 3, intArrayOf(8)),
            Tag(277, 3, intArrayOf(2)), Tag(284, 3, intArrayOf(1)),
            Tag(317, 3, intArrayOf(1)), Tag(322, 4, longArrayOf(512)),
            Tag(323, 4, longArrayOf(512)), Tag(324, 4, tileOffsets),
            Tag(325, 4, tileCounts), Tag(339, 3, intArrayOf(3, 3)),
            Tag(33550, 12, doubleArrayOf(1_000.0, 1_000.0, 0.0)),
            Tag(33922, 12, doubleArrayOf(0.0, 0.0, 0.0, -500.0002714332659, 499.9999123872258, 0.0)),
            Tag(34735, 3, geoKeys), Tag(34736, 12, geoDoubles),
            Tag(42113, 2, "-9999000\u0000".encodeToByteArray()),
        ).sortedBy { it.id }
        buffer.position(8)
        buffer.putShort(tags.size.toShort())
        var dataOffset = 8 + 2 + tags.size * 12 + 4
        tags.forEach { tag ->
            buffer.putShort(tag.id.toShort()); buffer.putShort(tag.type.toShort())
            val encoded = when (val value = tag.values) {
                is LongArray -> ByteBuffer.allocate(value.size * 4).order(order).also { out ->
                    value.forEach { out.putInt(it.toInt()) }
                }.array()
                is IntArray -> ByteBuffer.allocate(value.size * 2).order(order).also { out ->
                    value.forEach { out.putShort(it.toShort()) }
                }.array()
                is DoubleArray -> ByteBuffer.allocate(value.size * 8).order(order).also { out ->
                    value.forEach(out::putDouble)
                }.array()
                is ByteArray -> value
                else -> error("fixture type")
            }
            val count = when (val value = tag.values) {
                is LongArray -> value.size
                is IntArray -> value.size
                is DoubleArray -> value.size
                is ByteArray -> value.size
                else -> error("fixture type")
            }
            buffer.putInt(count)
            if (encoded.size <= 4) {
                buffer.put(encoded); repeat(4 - encoded.size) { buffer.put(0) }
            } else {
                buffer.putInt(dataOffset)
                encoded.copyInto(bytes, dataOffset)
                dataOffset += encoded.size
                if (dataOffset % 2 != 0) dataOffset++
            }
        }
        buffer.putInt(0)
        return bytes.copyOf(dataOffset)
    }
}
