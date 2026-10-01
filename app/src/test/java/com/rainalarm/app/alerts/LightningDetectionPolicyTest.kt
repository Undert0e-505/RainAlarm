package com.rainalarm.app.alerts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightningDetectionPolicyTest {
    @Test fun `named default radius is fifteen kilometres and propagates to policy`() {
        assertEquals(15.0, LightningDetectionPolicy.defaultRadiusKilometres, 0.0)
        assertEquals(
            LightningDetectionPolicy.defaultRadiusKilometres,
            LightningDetectionPolicy().radiusKilometres,
            0.0,
        )
    }

    @Test fun `geodesic circle includes boundary and rejects square corner`() {
        val radius = LightningDetectionPolicy.defaultRadiusKilometres
        val east = radius / 111.32
        assertTrue(GeoCirclePolicy.distanceKilometres(0.0, 0.0, 0.0, east) <= radius + 0.02)
        assertTrue(GeoCirclePolicy.distanceKilometres(0.0, 179.95, 0.0, -179.95) < radius)
        assertTrue(GeoCirclePolicy.distanceKilometres(0.0, 0.0, east, east) > radius)
    }

    @Test fun `detector tests pixels against circle rather than request square`() {
        val bounds = GeographicBounds(-0.2, -0.2, 0.2, 0.2)
        val coverage = GeographicBounds(-70.0, -70.0, 70.0, 70.0)
        val centre = ByteArray(101 * 101).apply { this[50 * 101 + 50] = 0xff.toByte() }
        assertEquals(
            LightningFrameObservation.DETECTED,
            LightningPixelDetector.evaluate(
                LightningPixelFrame(101, 101, centre, bounds, coverage, 1L),
                0.0,
                0.0,
                LightningDetectionPolicy(),
            ),
        )
        val corner = ByteArray(101 * 101).apply { this[5 * 101 + 95] = 0xff.toByte() }
        assertEquals(
            LightningFrameObservation.NO_DETECTION,
            LightningPixelDetector.evaluate(
                LightningPixelFrame(101, 101, corner, bounds, coverage, 1L),
                0.0,
                0.0,
                LightningDetectionPolicy(),
            ),
        )
    }

    @Test fun `curved full disk corners cannot establish a clear observation`() {
        val coverage = GeographicBounds(-70.0, -70.0, 70.0, 70.0)
        assertTrue(GeoCirclePolicy.circleCoveredByEllipticalFootprint(52.0, 0.0, 15.0, coverage))
        assertFalse(GeoCirclePolicy.circleCoveredByEllipticalFootprint(60.0, 60.0, 15.0, coverage))
    }

    @Test fun `catch up visits each unseen frame and first poll samples only latest`() {
        assertEquals(
            listOf(1_300L, 1_600L, 1_900L),
            LightningFrameCatchUpPolicy.frames(400L, 1_900L, 300L, 1_000L),
        )
        assertEquals(
            listOf(1_900L),
            LightningFrameCatchUpPolicy.frames(400L, 1_900L, 300L, null),
        )
        assertTrue(LightningFrameCatchUpPolicy.frames(400L, 1_900L, 300L, 1_900L).isEmpty())
    }

    @Test fun `lightning episode notifies first detection and rearms after clear`() {
        val firstDetection = LightningEpisodePolicy.reduce(
            LightningEpisodeState(),
            listOf(1_000L to LightningFrameObservation.DETECTED),
        )
        assertEquals(1_000L, firstDetection.newEventIdentity)
        assertTrue(firstDetection.state.active)
        val continued = LightningEpisodePolicy.reduce(
            firstDetection.state,
            listOf(1_100L to LightningFrameObservation.DETECTED),
        )
        assertEquals(null, continued.newEventIdentity)
        assertEquals(1_000L, continued.state.activeEventIdentity)
        val clear = LightningEpisodePolicy.reduce(
            continued.state,
            listOf(1_300L to LightningFrameObservation.NO_DETECTION),
        )
        assertFalse(clear.state.active)
        val triggered = LightningEpisodePolicy.reduce(
            clear.state,
            listOf(1_600L to LightningFrameObservation.DETECTED),
        )
        assertEquals(1_600L, triggered.newEventIdentity)
        assertEquals(
            null,
            LightningEpisodePolicy.reduce(
                triggered.state,
                listOf(1_900L to LightningFrameObservation.DETECTED),
            ).newEventIdentity,
        )
    }

    @Test fun `unavailable lightning evidence leaves episode unchanged`() {
        val active = LightningEpisodeState(active = true, activeEventIdentity = 1_000L)
        val result = LightningEpisodePolicy.reduce(
            active,
            listOf(1_300L to LightningFrameObservation.UNAVAILABLE),
        )
        assertEquals(active, result.state)
        assertEquals(null, result.newEventIdentity)
    }

    @Test fun `catch up reports the newest episode when clear separates detections`() {
        val result = LightningEpisodePolicy.reduce(
            LightningEpisodeState(),
            listOf(
                1_000L to LightningFrameObservation.DETECTED,
                1_300L to LightningFrameObservation.NO_DETECTION,
                1_600L to LightningFrameObservation.DETECTED,
            ),
        )

        assertEquals(1_600L, result.newEventIdentity)
        assertEquals(LightningEpisodeState(active = true, activeEventIdentity = 1_600L), result.state)
    }

    @Test fun `png validation accepts bounded structure and rejects checksum or dimensions`() {
        val png = png(160, 160)
        assertEquals(160, LightningPngValidator.validate(png, 160, 160).width)
        assertTrue(runCatching { LightningPngValidator.validate(png, 128, 160) }.isFailure)
        val damaged = png.copyOf().apply { this[lastIndex - 1] = (this[lastIndex - 1] + 1).toByte() }
        assertTrue(runCatching { LightningPngValidator.validate(damaged, 160, 160) }.isFailure)
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height)
            .put(8).put(6).put(0).put(0).put(0).array()
        chunk(output, "IHDR", header)
        chunk(output, "IDAT", byteArrayOf(0))
        chunk(output, "IEND", byteArrayOf())
        output.toByteArray()
    }

    private fun chunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
    }
}
