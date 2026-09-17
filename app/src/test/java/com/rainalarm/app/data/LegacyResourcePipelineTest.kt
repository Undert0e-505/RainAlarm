package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rainalarm.app.domain.ProviderRadarPointEvaluator

class LegacyResourcePipelineTest {
    private fun metadata(index: Int, forecast: Boolean = false) = RegionalManifestFrame(
        imageFilename = "observation/radar$index.jpg",
        velocityFilename = "observation/velocity$index.jpg",
        timestamp = 1_000L + index * 300L,
        modifiedAt = 1_000L + index * 300L,
        forecast = forecast,
    )

    @Test
    fun `transfer batches are bounded and prioritize now then forecast`() {
        val frames = (0 until 20).map { metadata(it, forecast = it >= 12) }
        val batches = LegacyFramePriority.boundedBatches(frames)
        assertTrue(batches.all { it.size <= 2 })
        val priority = batches.flatten()
        assertEquals(frames[11].timestamp, priority.first().timestamp)
        assertEquals(frames.drop(12).map { it.timestamp }, priority.drop(1).take(8).map { it.timestamp })
        assertEquals(frames.map { it.timestamp }.toSet(), priority.map { it.timestamp }.toSet())
    }

    @Test
    fun `decoded resource is released after success failure and cancellation`() {
        listOf<Throwable?>(null, IllegalStateException("upload"), java.util.concurrent.CancellationException()).forEach { failure ->
            var released = false
            runCatching {
                consumeAndRelease(Any(), { released = true }) {
                    if (failure != null) throw failure
                }
            }
            assertTrue(released)
        }
    }

    @Test
    fun `fifty legacy resources have a deterministic peak of one decoded resource`() {
        var active = 0
        var peak = 0
        repeat(50) {
            val decoded = Any().also { active++; peak = maxOf(peak, active) }
            consumeAndRelease(decoded, { active-- }) { /* fake texture upload */ }
        }
        assertEquals(0, active)
        assertEquals(1, peak)
    }

    @Test
    fun `archive retains compressed bytes and scalar samples in chronological order`() {
        val frames = listOf(
            LegacyCompressedFrame(0, RainViewerFrame(1000, "a", false), byteArrayOf(1, 2), byteArrayOf(3)),
            LegacyCompressedFrame(1, RainViewerFrame(1300, "b", true), byteArrayOf(4, 5), byteArrayOf(6)),
        )
        val archive = LegacyRadarArchive(
            frames,
            listOf(RadarPointSample(1000, false, 0f), RadarPointSample(1300, true, 0.5f)),
            totalCompressedBytes = 6,
        )
        assertEquals(listOf(1000L, 1300L), archive.frames.map { it.frame.time })
        assertEquals(listOf(0f, 0.5f), archive.pointSamples.map { it.intensity })
        assertFalse(archive.javaClass.declaredFields.any { it.type.name.contains("Bitmap") })
        // A second renderer/context can consume the same cached bytes without a network source.
        assertEquals(listOf(1.toByte(), 2.toByte()), archive.frames.first().radarJpeg.toList())
        assertEquals(listOf(1.toByte(), 2.toByte()), archive.frames.first().radarJpeg.toList())
    }

    @Test
    fun `provider alert sampling uses compact point timeline without bitmaps`() {
        val frames = listOf(
            LegacyCompressedFrame(0, RainViewerFrame(1000, "a", false), byteArrayOf(1), null),
            LegacyCompressedFrame(1, RainViewerFrame(1300, "b", true), byteArrayOf(2), null),
            LegacyCompressedFrame(2, RainViewerFrame(1600, "c", true), byteArrayOf(3), null),
        )
        val archive = LegacyRadarArchive(
            frames,
            listOf(
                RadarPointSample(1000, false, 0f),
                RadarPointSample(1300, true, 0f),
                RadarPointSample(1600, true, 0.8f),
            ),
            totalCompressedBytes = 3,
        )
        val session = RadarSession(
            place = DEFAULT_PLACE,
            regional = null,
            detail = null,
            motion = null,
            pairMotions = listOf(null, null),
            providerSelection = RadarProviderSelection(
                RadarProviderKind.METEOGROUP_REGIONAL,
                RadarProviderKind.METEOGROUP_REGIONAL,
            ),
            region = RegionalRadarAreas.all.first { it.id == "uk" },
            legacyArchive = archive,
        )
        val timeline = requireNotNull(ProviderRadarPointEvaluator.evaluate(session))
        assertFalse(timeline.currentlyWet)
        assertEquals(listOf(10), timeline.wetForecastMinutes)
        session.release()
    }
}
