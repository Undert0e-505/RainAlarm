package com.rainalarm.app.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.OpenCvSparseRadarTracker
import com.rainalarm.app.domain.OpenRadarColorScale
import com.rainalarm.app.domain.OpenRadarVectorPolicy
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarVelocityField
import com.rainalarm.app.domain.SparseRadarPoint
import com.rainalarm.app.domain.SparseRadarVector
import com.rainalarm.app.domain.WebMercator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot

/** Opt-in network smoke: -Pandroid.testInstrumentationRunnerArguments.liveOpera=true */
@RunWith(AndroidJUnit4::class)
class OpenRadarLiveProviderInstrumentedTest {
    @Test fun loadsRealRecentOperaFramesThroughNativeMotionPath() {
        runBlocking {
            assumeTrue(
                InstrumentationRegistry.getArguments().getString("liveOpera") == "true",
            )
            val started = System.nanoTime()
            val session = withTimeout(180_000L) {
                OperaRadarSessionLoader().load(
                    place = SavedPlace("Scotland native motion", 56.49, -4.20),
                    maxFrames = 5,
                    mode = RadarLoadMode.ALERT_ANALYSIS,
                )
            }
            try {
                assertEquals(RadarProviderKind.EUMETNET_OPERA, session.providerSelection.active)
                assertFalse(session.timelineFrames.isEmpty())
                val detail = session.denseVelocity.getValue(
                    com.rainalarm.app.domain.RadarResolutionTier.DETAIL,
                )
                android.util.Log.i(
                    "RainRadarNativeMotionTest",
                    "liveOpera frames=${session.timelineFrames.size} active=" +
                        "${detail.diagnostics?.activeLabel} vectors=" +
                        "${detail.diagnostics?.acceptedVectors}/${detail.diagnostics?.detectedVectors} " +
                        "runtimeMs=${detail.diagnostics?.runtimeMillis} totalMs=" +
                        "${(System.nanoTime() - started) / 1_000_000L}",
                )
            } finally {
                session.release("instrumentation-live-opera")
            }
        }
    }

    @Test fun benchmarksProductionNativePathAgainstWarmOperaSource() {
        runBlocking {
            assumeTrue(
                InstrumentationRegistry.getArguments().getString("benchmarkOpera") == "true",
            )
            val fixedNow = Instant.now()
            val loader = OperaRadarSessionLoader(now = { fixedNow })
            val place = SavedPlace("Scotland native motion", 56.49, -4.20)

            // Prime the process-wide immutable range cache. The measured production load then
            // issues the same requests as cache hits (the OPERA session log reports ranges=0).
            loader.load(
                place = place,
                maxFrames = 5,
                mode = RadarLoadMode.ALERT_ANALYSIS,
            ).release("instrumentation-benchmark-prime")

            val started = System.nanoTime()
            val native = loader.load(
                place = place,
                maxFrames = 5,
                mode = RadarLoadMode.ALERT_ANALYSIS,
            )
            val nativeMillis = (System.nanoTime() - started) / 1_000_000L
            val nativeDiagnostics = native.denseVelocity.values.last().diagnostics
            try {
                assertEquals("Native LK", nativeDiagnostics?.activeLabel)
                android.util.Log.i(
                    "RainRadarNativeMotionTest",
                    "warmOperaBenchmark nativeTotalMs=$nativeMillis nativeLkMs=" +
                        "${nativeDiagnostics?.runtimeMillis} nativeVectors=" +
                        "${nativeDiagnostics?.acceptedVectors}/" +
                        "${nativeDiagnostics?.detectedVectors}",
                )
            } finally {
                native.release("instrumentation-benchmark-native")
            }
        }
    }

    /**
     * Opt-in evidence for the reported multi-flow OPERA scene:
     * -Pandroid.testInstrumentationRunnerArguments.liveMultiFlow=true
     *
     * Integer patch registration is deliberately independent of the OpenCV tracker. Weather is
     * transient, so absence of useful rain or opposing observed shifts skips rather than turning a
     * data-dependent diagnostic into a release gate. The deterministic two-system OpenCV fixture
     * remains the regression gate.
    */
    @Test fun retainsDistinctRegionalMotionsWhenLiveSceneContainsThem() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("liveMultiFlow") == "true",
        )
        val skipReason: String? = runBlocking {
            val session = withTimeout(180_000L) {
                OperaRadarSessionLoader().load(
                    place = SavedPlace("France motion diagnostic", 46.5, 2.0),
                    maxFrames = 5,
                    mode = RadarLoadMode.SCREEN_TWO_TIER,
                )
            }
            try {
                val tier = session.regional
                    ?: return@runBlocking "Current OPERA session has no regional tier"
                val before = tier.frames[tier.frames.lastIndex - 1].bitmap
                val after = tier.frames.last().bitmap
                val field = session.velocity(RadarResolutionTier.REGIONAL)?.futureField
                    ?: return@runBlocking "Current OPERA session has no regional motion field"
                val switzerland = observeBoxMotion(
                    before,
                    after,
                    tier.bounds,
                    GeoBox(north = 47.9, west = 5.8, south = 45.6, east = 10.7),
                )
                val sardinia = observeBoxMotion(
                    before,
                    after,
                    tier.bounds,
                    GeoBox(north = 42.0, west = 7.5, south = 39.8, east = 10.2),
                )
                val diagnostics = session.velocity(RadarResolutionTier.REGIONAL)?.diagnostics
                val analysis = tier.frames.takeLast(3).map { bitmapAnalysisGrid(it.bitmap) }
                val tracks = analysis.zipWithNext().map { (first, second) ->
                    OpenCvSparseRadarTracker().track(first, second)
                }
                val rawVectors = tracks.flatMap { it.accepted }
                val cleanVectors = OpenRadarVectorPolicy.clean(rawVectors)
                val swissStages = regionStages(
                    tracks.flatMap { it.detectedPoints }, rawVectors, cleanVectors,
                    analysis.last(), tier.bounds,
                    GeoBox(north = 47.9, west = 5.8, south = 45.6, east = 10.7),
                )
                val sardiniaStages = regionStages(
                    tracks.flatMap { it.detectedPoints }, rawVectors, cleanVectors,
                    analysis.last(), tier.bounds,
                    GeoBox(north = 42.0, west = 7.5, south = 39.8, east = 10.2),
                )
                android.util.Log.i(
                    "RainRadarNativeMotionTest",
                    "liveMultiFlow times=${tier.frameTimes.takeLast(2)} " +
                        "Swiss=${switzerland?.report(field, tier.bounds)} " +
                        "Sardinia=${sardinia?.report(field, tier.bounds)} " +
                        "stagesSwiss=$swissStages stagesSardinia=$sardiniaStages " +
                        "regimes=${diagnostics?.spatialRegimes} " +
                        "separation=${diagnostics?.localBearingSeparationDegrees}",
                )
                val swiss = switzerland
                    ?: return@runBlocking "No usable Swiss rain patch in current OPERA scene"
                val sard = sardinia
                    ?: return@runBlocking "No usable Sardinia rain patch in current OPERA scene"
                if (hypot(sard.dx, sard.dy) < 0.75) {
                    return@runBlocking "Current Sardinia patch has no measurable displacement"
                }
                val observedDifference = hypot(
                    swiss.dx - sard.dx,
                    swiss.dy - sard.dy,
                )
                if (observedDifference < 1.25) {
                    return@runBlocking "Current scene has no independently distinct motion"
                }
                val swissNative = swiss.native(field, tier.bounds)
                val sardNative = sard.native(field, tier.bounds)
                val swissAngle = directionDifferenceDegrees(swiss.dx to swiss.dy, swissNative)
                val sardiniaAngle = directionDifferenceDegrees(sard.dx to sard.dy, sardNative)
                assertTrue(
                    "Native regional field collapsed distinct observed regimes: " +
                        "Swiss=$swissNative Sardinia=$sardNative",
                    hypot(
                        swissNative.first - sardNative.first,
                        swissNative.second - sardNative.second,
                    ) >= 0.65,
                )
                assertTrue(
                    "Native Swiss direction disagrees by $swissAngle degrees",
                    swissAngle <= 60.0,
                )
                assertTrue(
                    "Native Sardinia direction disagrees by $sardiniaAngle degrees",
                    sardiniaAngle <= 75.0,
                )
                if (abs(sard.dx) >= 0.75) {
                    assertTrue(
                        "Native Sardinia east/west sign disagrees with observed registration",
                        sard.dx * sardNative.first > 0.0,
                    )
                }
                null
            } finally {
                session.release("instrumentation-live-multi-flow")
            }
        }
        if (skipReason != null) {
            android.util.Log.i(
                "RainRadarNativeMotionTest",
                "liveMultiFlowComparisonUnavailable reason=$skipReason",
            )
        }
    }

    private data class GeoBox(
        val north: Double,
        val west: Double,
        val south: Double,
        val east: Double,
    )

    private data class ObservedMotion(
        val point: GeoPoint,
        val dx: Double,
        val dy: Double,
        val wetPixels: Int,
        val fitError: Double,
    ) {
        fun native(field: RadarVelocityField, bounds: GeoQuad): Pair<Double, Double> =
            normalized(point, bounds).let { (x, y) -> field.displacementAt(x, y) }

        fun report(field: RadarVelocityField, bounds: GeoQuad): String =
            "point=${"%.3f".format(point.latitude)},${"%.3f".format(point.longitude)} " +
                "observed=${"%.2f".format(dx)},${"%.2f".format(dy)} " +
                "native=${native(field, bounds).let { "%.2f,%.2f".format(it.first, it.second) }} " +
                "wet=$wetPixels mse=${"%.5f".format(fitError)}"
    }

    private data class RegionStages(
        val detected: Int,
        val forwardBackwardAccepted: Int,
        val cleansed: Int,
        val rawMedian: Pair<Double, Double>?,
        val cleanMedian: Pair<Double, Double>?,
    )

    private fun observeBoxMotion(
        before: Bitmap,
        after: Bitmap,
        bounds: GeoQuad,
        box: GeoBox,
    ): ObservedMotion? {
        val (left, top) = normalized(GeoPoint(box.north, box.west), bounds)
        val (right, bottom) = normalized(GeoPoint(box.south, box.east), bounds)
        val x0 = (left * before.width).toInt().coerceIn(0, before.width - 1)
        val x1 = (right * before.width).toInt().coerceIn(0, before.width - 1)
        val y0 = (top * before.height).toInt().coerceIn(0, before.height - 1)
        val y1 = (bottom * before.height).toInt().coerceIn(0, before.height - 1)
        if (x1 <= x0 || y1 <= y0) return null
        var weightedX = 0.0
        var weightedY = 0.0
        var weight = 0.0
        var wet = 0
        for (y in y0..y1) for (x in x0..x1) {
            val intensity = bitmapSeverity(before.getPixel(x, y)).toDouble()
            if (intensity >= OpenRadarColorScale.WET_SEVERITY_THRESHOLD) wet++
            weightedX += x * intensity
            weightedY += y * intensity
            weight += intensity
        }
        if (wet < 12 || weight <= 0.0) return null
        val centerX = (weightedX / weight).toInt()
        val centerY = (weightedY / weight).toInt()
        val radius = 28
        var bestDx = 0
        var bestDy = 0
        var bestError = Double.POSITIVE_INFINITY
        for (dy in -12..12) for (dx in -12..12) {
            var error = 0.0
            var samples = 0
            for (y in centerY - radius..centerY + radius step 2) {
                for (x in centerX - radius..centerX + radius step 2) {
                    val ax = x + dx
                    val ay = y + dy
                    if (x !in 0 until before.width || y !in 0 until before.height ||
                        ax !in 0 until after.width || ay !in 0 until after.height
                    ) continue
                    val first = bitmapSeverity(before.getPixel(x, y)).toDouble()
                    val second = bitmapSeverity(after.getPixel(ax, ay)).toDouble()
                    val delta = first - second
                    error += delta * delta
                    samples++
                }
            }
            if (samples > 400 && error / samples < bestError) {
                bestError = error / samples
                bestDx = dx
                bestDy = dy
            }
        }
        if (!bestError.isFinite()) return null
        val point = pixelPoint(centerX, centerY, before, bounds)
        return ObservedMotion(point, bestDx.toDouble(), bestDy.toDouble(), wet, bestError)
    }

    /** Mirrors OPERA's max-pool motion-analysis raster from its retained RG severity bitmap. */
    private fun bitmapAnalysisGrid(bitmap: Bitmap, maximumSize: Int = 384): IntensityGrid {
        val scale = ceil(maxOf(bitmap.width, bitmap.height) / maximumSize.toDouble())
            .toInt().coerceAtLeast(1)
        val width = ceil(bitmap.width / scale.toDouble()).toInt()
        val height = ceil(bitmap.height / scale.toDouble()).toInt()
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var maximum = 0f
            for (sourceY in y * scale until minOf((y + 1) * scale, bitmap.height)) {
                for (sourceX in x * scale until minOf((x + 1) * scale, bitmap.width)) {
                    maximum = maxOf(maximum, bitmapSeverity(bitmap.getPixel(sourceX, sourceY)))
                }
            }
            values[y * width + x] = maximum
        }
        return IntensityGrid(width, height, values)
    }

    private fun bitmapSeverity(argb: Int): Float {
        val shared = ((argb ushr 16) and 0xff) / 255f
        val coverage = (argb and 0xff) / 255f
        return OpenRadarColorScale.pointSeverity(shared, coverage)
    }

    private fun regionStages(
        points: List<SparseRadarPoint>,
        raw: List<SparseRadarVector>,
        clean: List<SparseRadarVector>,
        analysis: IntensityGrid,
        bounds: GeoQuad,
        box: GeoBox,
    ): RegionStages {
        val (left, top) = normalized(GeoPoint(box.north, box.west), bounds)
        val (right, bottom) = normalized(GeoPoint(box.south, box.east), bounds)
        fun inside(x: Double, y: Double): Boolean =
            x / analysis.width in left..right && y / analysis.height in top..bottom
        val regionalRaw = raw.filter { inside(it.x, it.y) }
        val regionalClean = clean.filter { inside(it.x, it.y) }
        fun medianVector(vectors: List<SparseRadarVector>): Pair<Double, Double>? =
            vectors.takeIf { it.isNotEmpty() }?.let { values ->
                values.map { it.dx }.sorted()[values.size / 2] to
                    values.map { it.dy }.sorted()[values.size / 2]
            }
        return RegionStages(
            detected = points.count { inside(it.x, it.y) },
            forwardBackwardAccepted = regionalRaw.size,
            cleansed = regionalClean.size,
            rawMedian = medianVector(regionalRaw),
            cleanMedian = medianVector(regionalClean),
        )
    }

    private fun pixelPoint(x: Int, y: Int, bitmap: Bitmap, bounds: GeoQuad): GeoPoint {
        val (worldLeft, worldTop) = WebMercator.worldFraction(bounds.topLeft)
        val (worldRight, worldBottom) = WebMercator.worldFraction(bounds.bottomRight)
        return WebMercator.pointAtWorldFraction(
            worldLeft + (x + 0.5) / bitmap.width * (worldRight - worldLeft),
            worldTop + (y + 0.5) / bitmap.height * (worldBottom - worldTop),
        )
    }

    private fun directionDifferenceDegrees(
        first: Pair<Double, Double>,
        second: Pair<Double, Double>,
    ): Double {
        val denominator = hypot(first.first, first.second) * hypot(second.first, second.second)
        if (denominator <= 1e-9) return 180.0
        val cosine = ((first.first * second.first + first.second * second.second) / denominator)
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private companion object {
        fun normalized(point: GeoPoint, bounds: GeoQuad): Pair<Double, Double> {
            val (left, top) = WebMercator.worldFraction(bounds.topLeft)
            val (right, bottom) = WebMercator.worldFraction(bounds.bottomRight)
            val (x, y) = WebMercator.worldFraction(point)
            return (x - left) / (right - left) to (y - top) / (bottom - top)
        }
    }
}
