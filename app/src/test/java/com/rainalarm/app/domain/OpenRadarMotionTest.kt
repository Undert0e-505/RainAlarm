package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class OpenRadarMotionTest {
    @Test fun `history accepts regular five and ten minute cadence and rejects irregular`() {
        assertEquals(300L, requireNotNull(OpenRadarHistoryPolicy.latestRegular(
            frames(0L, 300L, 600L), expectedCadenceSeconds = 300L,
        )).cadenceSeconds)
        assertEquals(600L, requireNotNull(OpenRadarHistoryPolicy.latestRegular(
            frames(0L, 600L, 1200L), expectedCadenceSeconds = 600L,
        )).cadenceSeconds)
        assertNull(OpenRadarHistoryPolicy.latestRegular(
            frames(0L, 300L, 910L), expectedCadenceSeconds = 300L,
        ))
    }

    @Test fun `pooled vectors are cleansed interpolated and encoded with subpixel precision`() {
        val tracker = SparseRadarTracker { _, _ -> syntheticTracks(dx = 0.4, dy = -0.25) }
        val result = NativeOpenRadarMotionEstimator(tracker).estimate(
            frames(0L, 300L, 600L),
            sourceWidth = 512,
            sourceHeight = 512,
            expectedCadenceSeconds = 300L,
        )
        val field = requireNotNull(result.field)
        val displacement = field.displacementAt(0.5, 0.5)
        assertEquals(3.2, displacement.first, 0.12)
        assertEquals(-2.0, displacement.second, 0.12)
        assertEquals(300L, field.sourceIntervalSeconds)
        assertTrue(field.width >= 32)
        assertTrue(result.diagnostics.acceptedVectors >= 8)
    }

    @Test fun `robust cleansing removes extreme local and global vectors`() {
        val coherent = syntheticTracks(0.8, -0.3).accepted
        val dirty = coherent + SparseRadarVector(40.0, 40.0, 50.0, -50.0)
        val cleaned = OpenRadarVectorPolicy.clean(dirty)
        assertTrue(cleaned.isNotEmpty())
        assertFalse(cleaned.any { abs(it.dx) > 5.0 || abs(it.dy) > 5.0 })
    }

    @Test fun `smaller separated opposing motion regime survives cleansing and interpolation`() {
        val dominant = vectorPatch(
            centerX = 58.0,
            centerY = 58.0,
            columns = 5,
            rows = 5,
            spacing = 12.0,
            dx = 2.2,
            dy = 1.1,
        )
        val minority = vectorPatch(
            centerX = 202.0,
            centerY = 196.0,
            columns = 3,
            rows = 3,
            spacing = 12.0,
            dx = -1.6,
            dy = -0.35,
        )
        val cleaned = OpenRadarVectorPolicy.clean(
            dominant + minority + SparseRadarVector(126.0, 124.0, 30.0, -30.0),
        )
        val regimes = OpenRadarVectorPolicy.regimes(cleaned)
        assertEquals(2, regimes.size)
        assertTrue(cleaned.count { it.x < 120.0 && it.dx > 1.5 } >= 8)
        assertTrue(cleaned.count { it.x > 150.0 && it.dx < -1.0 } >= 4)
        assertTrue(requireNotNull(
            OpenRadarVectorPolicy.maximumRegimeBearingSeparation(regimes),
        ) > 100.0)

        val dense = OpenRadarVectorPolicy.interpolate(
            cleaned,
            outputWidth = 64,
            outputHeight = 64,
            sourceWidth = 256,
            sourceHeight = 256,
            sourceIntervalSeconds = 300L,
        )
        val dominantLocal = dense.closestTo(58.0, 58.0)
        val minorityLocal = dense.closestTo(202.0, 196.0)
        assertTrue(dominantLocal.dx > 1.5 && dominantLocal.dy > 0.6)
        assertTrue(minorityLocal.dx < -1.0 && minorityLocal.dy < -0.1)
    }

    @Test fun `component corridors support forward warp without bleeding across clear gap`() {
        val west = vectorPatch(36.0, 42.0, 3, 3, 10.0, 3.0, 0.1)
        val east = vectorPatch(220.0, 210.0, 3, 3, 10.0, -3.0, -0.1)
        val cleaned = OpenRadarVectorPolicy.clean(west + east)
        val dense = OpenRadarVectorPolicy.interpolate(
            cleaned,
            64,
            64,
            256,
            256,
            sourceIntervalSeconds = 300L,
        )
        assertTrue(dense.closestTo(42.0, 42.0).dx > 2.5)
        assertTrue(dense.closestTo(214.0, 210.0).dx < -2.5)
        val unsupportedGap = dense.closestTo(128.0, 128.0)
        assertEquals(0.0, unsupportedGap.dx, 1e-9)
        assertEquals(0.0, unsupportedGap.dy, 1e-9)
        // Twelve five-minute steps ahead is beyond the static support radius but remains inside
        // the deliberately swept support corridor required by backward warping.
        assertTrue(dense.closestTo(78.0, 43.0).dx > 2.5)
    }

    @Test fun `local contributor radius prevents bleed inside one chain-connected component`() {
        val dominant = vectorPatch(55.0, 55.0, 5, 5, 10.0, 2.8, 0.4)
        val bridge = listOf(
            SparseRadarVector(115.0, 95.0, 0.2, 0.1),
            SparseRadarVector(155.0, 130.0, 0.15, 0.15),
            SparseRadarVector(190.0, 160.0, 0.1, 0.2),
        )
        val minority = vectorPatch(215.0, 188.0, 3, 3, 10.0, -1.7, -0.5)
        val connected = dominant + bridge + minority
        assertEquals(1, OpenRadarVectorPolicy.regimes(connected).size)
        val dense = OpenRadarVectorPolicy.interpolate(connected, 64, 64, 256, 256, 300L)
        val dominantLocal = dense.closestTo(55.0, 55.0)
        val minorityLocal = dense.closestTo(215.0, 188.0)
        assertTrue(dominantLocal.dx > 2.4)
        assertTrue(
            "distant dominant vectors crossed the connected support: $minorityLocal",
            minorityLocal.dx < -1.3 && minorityLocal.dy < -0.25,
        )
    }

    @Test fun `single coherent flow retains local outlier rejection and dense support`() {
        val flow = vectorPatch(96.0, 96.0, 5, 5, 11.0, 0.9, -0.45)
        val cleaned = OpenRadarVectorPolicy.clean(
            flow + SparseRadarVector(96.0, 96.0, -12.0, 18.0),
        )
        assertEquals(1, OpenRadarVectorPolicy.regimes(cleaned).size)
        assertFalse(cleaned.any { abs(it.dx) > 4.0 || abs(it.dy) > 4.0 })
        val dense = OpenRadarVectorPolicy.interpolate(cleaned, 64, 64, 256, 256, 300L)
        val local = dense.closestTo(96.0, 96.0)
        assertEquals(0.9, local.dx, 0.08)
        assertEquals(-0.45, local.dy, 0.08)
    }

    @Test fun `adaptive encoding bounds saturation while retaining useful precision`() {
        assertEquals(4f, OpenRadarVelocityEncoding.scaleFor(0.2), 0f)
        assertEquals(10.5f, OpenRadarVelocityEncoding.scaleFor(10.0), 0f)
        val scale = OpenRadarVelocityEncoding.scaleFor(6.37)
        val decoded = RadarVelocityField.decodeChannel(
            RadarVelocityField.encodeChannel(2.43, scale),
        ) * scale
        assertEquals(2.43, decoded, 0.06)
    }

    @Test fun `native failure is explicit and uses exact supplied legacy future`() {
        val legacy = constantField(8.0, 0.0, 300L)
        val selected = OpenRadarFutureFieldSelector.select(
            legacyPairFields = listOf(legacy),
            nativeFrames = emptyList(),
            sourceWidth = 512,
            sourceHeight = 512,
            expectedCadenceSeconds = 300L,
            trackerFactory = { error("tracker must not be called without history") },
        )
        assertEquals(legacy.sourceIntervalSeconds, selected.futureField?.sourceIntervalSeconds)
        assertTrue(legacy.channels.contentEquals(requireNotNull(selected.futureField).channels))
        assertNotNull(selected.diagnostics?.fallbackReason)
        assertTrue(selected.diagnostics?.activeLabel?.contains("fallback") == true)
    }

    @Test fun `production selector always prefers reliable native field over legacy aggregate`() {
        val legacy = constantField(8.0, 0.0, 300L)
        val selected = OpenRadarFutureFieldSelector.select(
            legacyPairFields = listOf(legacy),
            nativeFrames = frames(0L, 300L, 600L),
            sourceWidth = 512,
            sourceHeight = 512,
            expectedCadenceSeconds = 300L,
            trackerFactory = { SparseRadarTracker { _, _ -> syntheticTracks(0.4, -0.25) } },
        )
        val displacement = requireNotNull(selected.futureField).displacementAt(0.5, 0.5)
        assertEquals(3.2, displacement.first, 0.12)
        assertEquals(-2.0, displacement.second, 0.12)
        assertEquals("Native LK", selected.diagnostics?.activeLabel)
        assertNull(selected.diagnostics?.fallbackReason)
    }

    @Test fun `legacy 64px integer motion aliases one cell to 96 kilometres per hour`() {
        val before = texturedGrid(64, 64)
        val after = translated(before, 1, 0)
        val legacy = requireNotNull(DenseRadarMotionEstimator.estimatePair(
            TimedIntensityGrid(0L, before),
            TimedIntensityGrid(300L, after),
            sourceWidth = 512,
            sourceHeight = 512,
        ))
        val legacyDx = legacy.displacementAt(0.5, 0.5).first
        val legacyKilometresPerHour = abs(legacyDx) * 12.0
        assertEquals(96.0, legacyKilometresPerHour, 3.0)

        val native = requireNotNull(NativeOpenRadarMotionEstimator(
            SparseRadarTracker { _, _ -> syntheticTracks(0.4, 0.0) },
        ).estimate(
            frames(0L, 300L, 600L), 512, 512, 300L,
        ).field)
        val nativeKilometresPerHour = abs(native.displacementAt(0.5, 0.5).first) * 12.0
        assertEquals(38.4, nativeKilometresPerHour, 1.5)
        assertTrue(nativeKilometresPerHour < legacyKilometresPerHour / 2.0)
    }

    private fun frames(vararg times: Long): List<TimedIntensityGrid> =
        times.map { TimedIntensityGrid(it, texturedGrid(64, 64)) }

    private fun syntheticTracks(dx: Double, dy: Double): SparseRadarTrackResult {
        val vectors = buildList {
            for (y in listOf(8.0, 30.0, 52.0)) {
                for (x in listOf(8.0, 30.0, 52.0)) {
                    add(SparseRadarVector(x, y, dx, dy, 0.05))
                }
            }
        }
        return SparseRadarTrackResult(vectors.size, vectors, 1L)
    }

    private fun vectorPatch(
        centerX: Double,
        centerY: Double,
        columns: Int,
        rows: Int,
        spacing: Double,
        dx: Double,
        dy: Double,
    ): List<SparseRadarVector> = buildList {
        val startX = centerX - (columns - 1) * spacing / 2.0
        val startY = centerY - (rows - 1) * spacing / 2.0
        repeat(rows) { row ->
            repeat(columns) { column ->
                add(SparseRadarVector(
                    x = startX + column * spacing,
                    y = startY + row * spacing,
                    dx = dx + (column - columns / 2) * 0.015,
                    dy = dy + (row - rows / 2) * 0.015,
                    forwardBackwardError = 0.05,
                ))
            }
        }
    }

    private fun List<SparseRadarVector>.closestTo(x: Double, y: Double): SparseRadarVector =
        minBy { vector -> hypot(vector.x - x, vector.y - y) }

    private fun texturedGrid(width: Int, height: Int): IntensityGrid = IntensityGrid(
        width,
        height,
        FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x < 5 || y < 5 || x >= width - 5 || y >= height - 5) 0f
            else (((x * 37 + y * 19 + (x * y) % 23) % 97) / 96f).coerceAtLeast(0.09f)
        },
    )

    private fun translated(source: IntensityGrid, dx: Int, dy: Int): IntensityGrid {
        val values = FloatArray(source.values.size)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val sx = x - dx
            val sy = y - dy
            if (sx in 0 until source.width && sy in 0 until source.height) {
                values[y * source.width + x] = source[sx, sy]
            }
        }
        return IntensityGrid(source.width, source.height, values)
    }

    private fun constantField(dx: Double, dy: Double, interval: Long): RadarVelocityField {
        val scale = maxOf(4f, hypot(dx, dy).toFloat() * 1.1f)
        val channels = ByteArray(8 * 8 * 2)
        repeat(64) { index ->
            channels[index * 2] = RadarVelocityField.encodeChannel(dx, scale)
            channels[index * 2 + 1] = RadarVelocityField.encodeChannel(dy, scale)
        }
        return RadarVelocityField(8, 8, channels, scale, interval, 0.9)
    }
}
