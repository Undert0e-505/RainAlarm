package com.rainalarm.app.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class OpenCvRadarTrackerInstrumentedTest {
    @Test fun loadsBundledNativeLibraryAndRecoversSubpixelThreeFrameTranslation() {
        assertTrue(OpenCvRuntime.failureMessage.orEmpty(), OpenCvRuntime.ensureLoaded())
        val base = texturedGrid(SIZE, SIZE)
        val frames = listOf(
            TimedIntensityGrid(0L, base),
            TimedIntensityGrid(300L, shifted(base, DX, DY)),
            TimedIntensityGrid(600L, shifted(base, DX * 2.0, DY * 2.0)),
        )
        val result = NativeOpenRadarMotionEstimator(OpenCvSparseRadarTracker()).estimate(
            frames,
            sourceWidth = SIZE,
            sourceHeight = SIZE,
            expectedCadenceSeconds = 300L,
        )
        assertNotNull(result.diagnostics.fallbackReason, result.field)
        val displacement = requireNotNull(result.field).displacementAt(0.5, 0.5)
        android.util.Log.i(
            "RainRadarNativeMotionTest",
            "expected=$DX,$DY recovered=${displacement.first},${displacement.second} " +
                "vectors=${result.diagnostics.acceptedVectors}/${result.diagnostics.detectedVectors} " +
                "runtimeMs=${result.diagnostics.runtimeMillis}",
        )
        assertEquals(DX, displacement.first, 0.35)
        assertEquals(DY, displacement.second, 0.35)
        assertTrue(result.diagnostics.acceptedVectors >= 8)
    }

    @Test fun stationaryClearAndUnknownEdgeInputsFailSafely() {
        assertTrue(OpenCvRuntime.ensureLoaded())
        val tracker = OpenCvSparseRadarTracker()
        val base = texturedGrid(SIZE, SIZE)
        val stationary = tracker.track(base, base)
        assertTrue(stationary.accepted.isNotEmpty())
        assertTrue(stationary.accepted.map { abs(it.dx) + abs(it.dy) }.sorted()
            [stationary.accepted.size / 2] < 0.12)

        val clear = IntensityGrid(SIZE, SIZE, FloatArray(SIZE * SIZE))
        assertEquals(0, tracker.track(clear, clear).detected)

        val unknownEdgeValues = base.values.copyOf()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (x < 8 || y < 8 || x >= SIZE - 8 || y >= SIZE - 8) {
                unknownEdgeValues[y * SIZE + x] = Float.NaN
            }
        }
        val unknown = IntensityGrid(SIZE, SIZE, unknownEdgeValues)
        val edgeResult = tracker.track(unknown, shifted(unknown, 0.75, -0.4))
        assertTrue(edgeResult.accepted.all {
            it.x >= 5 && it.y >= 5 && it.x < SIZE - 5 && it.y < SIZE - 5
        })
    }

    @Test fun retainsLargeAndMinoritySystemsMovingInOppositeDirections() {
        assertTrue(OpenCvRuntime.ensureLoaded())
        val frames = listOf(0.0, 1.0, 2.0).mapIndexed { index, step ->
            TimedIntensityGrid(index * 300L, twoSystemFrame(step))
        }
        val result = NativeOpenRadarMotionEstimator(OpenCvSparseRadarTracker()).estimate(
            frames,
            sourceWidth = MULTI_SIZE,
            sourceHeight = MULTI_SIZE,
            expectedCadenceSeconds = 300L,
        )
        val field = requireNotNull(result.field) {
            result.diagnostics.fallbackReason ?: "Native multi-flow field was unavailable"
        }
        val large = field.displacementAt(75.0 / MULTI_SIZE, 55.0 / MULTI_SIZE)
        val minority = field.displacementAt(210.0 / MULTI_SIZE, 190.0 / MULTI_SIZE)
        val unsupportedMidpoint = field.displacementAt(0.5, 0.5)
        android.util.Log.i(
            "RainRadarNativeMotionTest",
            "multiFlow large=${large.first},${large.second} " +
                "minority=${minority.first},${minority.second} " +
                "midpoint=${unsupportedMidpoint.first},${unsupportedMidpoint.second} " +
                "regimes=${result.diagnostics.spatialRegimes} separation=" +
                "${result.diagnostics.localBearingSeparationDegrees} vectors=" +
                "${result.diagnostics.acceptedVectors}/${result.diagnostics.detectedVectors}",
        )
        assertTrue("dominant local eastward flow was lost: $large", large.first > 0.75)
        assertTrue("dominant local southward flow was lost: $large", large.second > 0.25)
        assertTrue("minority local westward flow was lost: $minority", minority.first < -0.55)
        assertTrue("minority local northward flow was lost: $minority", minority.second < -0.3)
        assertEquals(0.0, unsupportedMidpoint.first, 0.04)
        assertEquals(0.0, unsupportedMidpoint.second, 0.04)
        assertTrue(result.diagnostics.spatialRegimes >= 2)
        assertTrue(requireNotNull(result.diagnostics.localBearingSeparationDegrees) > 100.0)
    }

    @Test fun spatiallyBalancedDetectionRetainsWeakerMinoritySystem() {
        assertTrue(OpenCvRuntime.ensureLoaded())
        val frames = listOf(0.0, 1.0, 2.0).mapIndexed { index, step ->
            TimedIntensityGrid(index * 300L, imbalancedTextureFrame(step))
        }
        val tracker = OpenCvSparseRadarTracker()
        val firstPair = tracker.track(frames[0].grid, frames[1].grid)
        val minorityFeatures = firstPair.detectedPoints.count { it.x > 176.0 && it.y > 148.0 }
        val result = NativeOpenRadarMotionEstimator(tracker).estimate(
            frames,
            sourceWidth = MULTI_SIZE,
            sourceHeight = MULTI_SIZE,
            expectedCadenceSeconds = 300L,
        )
        val field = requireNotNull(result.field) {
            result.diagnostics.fallbackReason ?: "Native imbalanced-flow field was unavailable"
        }
        val dominant = field.displacementAt(75.0 / MULTI_SIZE, 55.0 / MULTI_SIZE)
        val minority = field.displacementAt(210.0 / MULTI_SIZE, 190.0 / MULTI_SIZE)
        android.util.Log.i(
            "RainRadarNativeMotionTest",
            "imbalancedFlow minorityFeatures=$minorityFeatures dominant=$dominant " +
                "minority=$minority regimes=${result.diagnostics.spatialRegimes}",
        )
        assertTrue("weaker minority received too few local corners: $minorityFeatures", minorityFeatures >= 4)
        assertTrue("dominant local flow was lost: $dominant", dominant.first > 0.75)
        assertTrue("weaker minority westward flow was lost: $minority", minority.first < -0.45)
        assertTrue("weaker minority northward flow was lost: $minority", minority.second < -0.2)
        assertTrue(result.diagnostics.spatialRegimes >= 2)
    }

    private fun twoSystemFrame(step: Double): IntensityGrid {
        val values = FloatArray(MULTI_SIZE * MULTI_SIZE)
        fun paint(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            dx: Double,
            dy: Double,
            seed: Int,
        ) {
            for (y in 0 until MULTI_SIZE) for (x in 0 until MULTI_SIZE) {
                val sourceX = x - dx * step
                val sourceY = y - dy * step
                if (sourceX !in left.toDouble()..right.toDouble() ||
                    sourceY !in top.toDouble()..bottom.toDouble()
                ) continue
                val cellX = sourceX.toInt() / 10
                val cellY = sourceY.toInt() / 10
                val localX = sourceX.toInt() % 10
                val localY = sourceY.toInt() % 10
                val texture = ((cellX * 31 + cellY * 43 + cellX * cellY * seed) % 13) / 12f
                values[y * MULTI_SIZE + x] = if (localX in 2..8 && localY in 2..8) {
                    0.16f + texture * 0.78f
                } else 0.05f
            }
        }
        paint(15, 15, 135, 95, 1.35, 0.65, 5)
        paint(182, 155, 238, 222, -1.1, -0.95, 9)
        return IntensityGrid(MULTI_SIZE, MULTI_SIZE, values)
    }

    private fun imbalancedTextureFrame(step: Double): IntensityGrid {
        val values = twoSystemFrame(step).values.copyOf()
        // Repaint the minority with far lower contrast and a wider texture cell, giving it many
        // fewer corners than the dominant band. A global Shi-Tomasi ranking discards this system.
        for (y in 145 until 232) for (x in 172 until 245) values[y * MULTI_SIZE + x] = 0f
        for (y in 0 until MULTI_SIZE) for (x in 0 until MULTI_SIZE) {
            val sourceX = x + 1.1 * step
            val sourceY = y + 0.95 * step
            if (sourceX !in 182.0..238.0 || sourceY !in 155.0..222.0) continue
            val cellX = sourceX.toInt() / 16
            val cellY = sourceY.toInt() / 16
            val localX = sourceX.toInt() % 16
            val localY = sourceY.toInt() % 16
            val texture = ((cellX * 7 + cellY * 11 + cellX * cellY * 3) % 5) / 4f
            values[y * MULTI_SIZE + x] = if (localX in 3..12 && localY in 3..12) {
                0.05f + texture * 0.13f
            } else 0.035f
        }
        return IntensityGrid(MULTI_SIZE, MULTI_SIZE, values)
    }

    private fun texturedGrid(width: Int, height: Int): IntensityGrid {
        val values = FloatArray(width * height)
        for (y in 10 until height - 10) for (x in 10 until width - 10) {
            val cellX = x / 12
            val cellY = y / 12
            val localX = x % 12
            val localY = y % 12
            val cellValue = ((cellX * 29 + cellY * 47 + cellX * cellY * 7) % 11) / 10f
            values[y * width + x] = if (localX in 2..9 && localY in 2..9) {
                0.12f + cellValue * 0.78f
            } else 0.04f
        }
        return IntensityGrid(width, height, values)
    }

    private fun shifted(source: IntensityGrid, dx: Double, dy: Double): IntensityGrid {
        val output = FloatArray(source.values.size)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val sx = x - dx
            val sy = y - dy
            if (sx < 0 || sy < 0 || sx >= source.width - 1 || sy >= source.height - 1) continue
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val fx = sx - x0
            val fy = sy - y0
            val v00 = source[x0, y0]
            val v10 = source[x0 + 1, y0]
            val v01 = source[x0, y0 + 1]
            val v11 = source[x0 + 1, y0 + 1]
            output[y * source.width + x] = if (
                listOf(v00, v10, v01, v11).any { !it.isFinite() }
            ) Float.NaN else (
                v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
                    v01 * (1 - fx) * fy + v11 * fx * fy
                ).toFloat()
        }
        return IntensityGrid(source.width, source.height, output)
    }

    private companion object {
        const val SIZE = 192
        const val MULTI_SIZE = 256
        const val DX = 1.35
        const val DY = -0.65
    }
}
