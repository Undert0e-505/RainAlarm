package com.rainalarm.app.data

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteFrameCacheTest {
    private fun png(width: Int = 2, height: Int = 2, colour: Int = 0x8044AACC.toInt()): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        repeat(width) { x -> repeat(height) { y -> image.setRGB(x, y, colour + x + y) } }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun request(
        identity: String,
        valid: Long = 1_000L,
        width: Int = 2,
        height: Int = 2,
    ) = SatelliteFrameAssetRequest(
        identity,
        "https://view.eumetsat.int/geoserver/wms?frame=$identity",
        width,
        height,
        EumetLayerMetadata(
            RadarMapLayer.FOG, valid, -15.0, 47.2, 6.0, 63.3,
            EumetProduct.FOG_LOW_CLOUD,
        ),
    )

    @Test fun `PNG validation rejects corrupt truncated and wrong dimension content`() {
        val valid = png()
        SatellitePngPolicy.requireValid(valid, 2, 2)
        val corrupt = valid.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            SatellitePngPolicy.requireValid(corrupt, 2, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SatellitePngPolicy.requireValid(valid.copyOf(valid.size - 4), 2, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SatellitePngPolicy.requireValid(valid, 3, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SatelliteFrameResponsePolicy.requireBody(
                SatelliteFrameResponse(200, "text/xml", valid.size.toLong(), valid), request("a"),
            )
        }
    }

    @Test fun `cache commits atomically cleans temporary files and rejects corrupt hits`() {
        val directory = Files.createTempDirectory("satellite-cache").toFile()
        try {
            val stray = directory.resolve(".stray.tmp-incomplete").apply { writeText("partial") }
            val cache = SatelliteDiskCache(directory)
            assertEquals(128L * 1024L * 1024L, cache.maximumBytes)
            assertFalse(stray.exists())
            val request = request("atomic")
            val expected = png()
            val stored = cache.put(request, expected)
            assertTrue(stored.name == "${request.diskKey}.png")
            assertFalse(directory.listFiles().orEmpty().any { it.name.contains(".tmp-") })
            assertArrayEquals(expected, cache.get(request)?.readBytes())
            stored.writeBytes(expected.copyOf(expected.size - 3))
            assertNull(cache.get(request))
            assertFalse(stored.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `LRU is bounded and canonical identity is stable`() {
        val bytes = png()
        val clock = AtomicLong(1_000L)
        val directory = Files.createTempDirectory("satellite-lru").toFile()
        try {
            val first = request("first")
            val same = request("first")
            val second = request("second", valid = 1_600L)
            val third = request("third", valid = 2_200L)
            assertEquals(first.diskKey, same.diskKey)
            assertFalse(first.diskKey == second.diskKey)
            val cache = SatelliteDiskCache(
                directory, maximumBytes = bytes.size.toLong() * 2,
                clockMillis = { clock.incrementAndGet() },
            )
            cache.put(first, bytes)
            cache.put(second, bytes)
            assertNotNull(cache.get(first)) // make first newest
            cache.put(third, bytes)
            assertNotNull(cache.get(first))
            assertNull(cache.get(second))
            assertNotNull(cache.get(third))
            assertTrue(cache.sizeBytes() <= bytes.size.toLong() * 2)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `store verifies the complete set and cache hits avoid network`() = runBlocking {
        val directory = Files.createTempDirectory("satellite-store").toFile()
        try {
            val calls = AtomicInteger()
            val body = png()
            val transport = SatelliteFrameTransport {
                calls.incrementAndGet()
                SatelliteFrameResponse(200, "image/png", body.size.toLong(), body)
            }
            val store = SatelliteFrameStore(SatelliteDiskCache(directory), transport)
            val requests = listOf(request("one"), request("two", 1_600L))
            val progress = mutableListOf<Pair<Int, Int>>()
            val first = store.prepare(requests) { ready, total -> progress += ready to total }
            assertEquals(2, first.size)
            assertEquals(0 to 2, progress.first())
            assertEquals(2 to 2, progress.last())
            assertEquals(2, calls.get())
            val second = store.prepare(requests)
            assertEquals(first.map { it.request.diskKey }, second.map { it.request.diskKey })
            assertEquals(2, calls.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `combined cloud and lightning downloads obey the global concurrency bound`() = runBlocking {
        val directory = Files.createTempDirectory("satellite-concurrency").toFile()
        try {
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val body = png()
            val transport = SatelliteFrameTransport {
                val now = active.incrementAndGet()
                maximum.updateAndGet { old -> maxOf(old, now) }
                delay(30)
                active.decrementAndGet()
                SatelliteFrameResponse(200, "image/png", body.size.toLong(), body)
            }
            val store = SatelliteFrameStore(
                SatelliteDiskCache(directory), transport, concurrency = 2,
            )
            val cloudRequests = (0 until 4).map { request("cloud-$it", 1_000L + it * 600) }
            val lightningRequests = (0 until 4).map { request("lightning-$it", 1_000L + it * 300) }
            coroutineScope {
                listOf(
                    async { store.prepare(cloudRequests) },
                    async { store.prepare(lightningRequests) },
                ).awaitAll()
            }
            assertEquals(2, maximum.get())
            assertEquals(2, store.concurrency)
            assertEquals(3, SatelliteFrameStore.maximumConcurrency)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `malformed download never advances full-set readiness`() = runBlocking {
        val directory = Files.createTempDirectory("satellite-invalid").toFile()
        try {
            val progress = mutableListOf<Pair<Int, Int>>()
            val store = SatelliteFrameStore(
                SatelliteDiskCache(directory),
                SatelliteFrameTransport {
                    SatelliteFrameResponse(200, "image/png", 7, "partial".toByteArray())
                },
            )
            val failure = runCatching {
                store.prepare(listOf(request("broken"))) { ready, total ->
                    progress += ready to total
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(listOf(0 to 1), progress)
            assertEquals(0L, directory.listFiles().orEmpty()
                .filter { it.extension == "png" }.sumOf { it.length() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `a plan is not ready unless its entire verified set coexists on disk`() = runBlocking {
        val directory = Files.createTempDirectory("satellite-whole-set").toFile()
        try {
            val body = png()
            val store = SatelliteFrameStore(
                SatelliteDiskCache(directory, maximumBytes = body.size.toLong()),
                SatelliteFrameTransport {
                    SatelliteFrameResponse(200, "image/png", body.size.toLong(), body)
                },
            )
            val failure = runCatching {
                store.prepare(listOf(request("first"), request("second", 1_600L)))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(directory.listFiles().orEmpty()
                .filter { it.extension == "png" }.sumOf { it.length() } <= body.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `corrupt or evicted files are fetched again`() = runBlocking {
        val directory = Files.createTempDirectory("satellite-recovery").toFile()
        try {
            val calls = AtomicInteger()
            val body = png()
            val request = request("recover")
            val store = SatelliteFrameStore(
                SatelliteDiskCache(directory),
                SatelliteFrameTransport {
                    calls.incrementAndGet()
                    SatelliteFrameResponse(200, "image/png", body.size.toLong(), body)
                },
            )
            val first = store.prepare(listOf(request)).single()
            assertEquals(1, calls.get())
            first.file.writeText("not a png")
            val recovered = store.prepare(listOf(request)).single()
            assertEquals(2, calls.get())
            assertTrue(recovered.file.isFile)
            assertTrue(store.invalidate(request))
            store.prepare(listOf(request))
            assertEquals(3, calls.get())
        } finally {
            directory.deleteRecursively()
        }
    }
}
