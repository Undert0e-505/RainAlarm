package com.rainalarm.app.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SparseRadarVector(
    val x: Double,
    val y: Double,
    val dx: Double,
    val dy: Double,
    val forwardBackwardError: Double = 0.0,
)

data class SparseRadarPoint(val x: Double, val y: Double)

data class SparseRadarTrackResult(
    val detected: Int,
    val accepted: List<SparseRadarVector>,
    val runtimeMillis: Long,
    /** Feature coordinates before forward/backward validity filtering; diagnostic only. */
    val detectedPoints: List<SparseRadarPoint> = emptyList(),
)

/** Android/OpenCV is hidden behind this interface so all policy and field logic stays JVM-testable. */
fun interface SparseRadarTracker {
    fun track(before: IntensityGrid, after: IntensityGrid): SparseRadarTrackResult
}

data class OpenRadarMotionDiagnostics(
    val activeLabel: String,
    val cadenceSeconds: Long?,
    val historyFrames: Int,
    val detectedVectors: Int,
    val acceptedVectors: Int,
    val medianSpeedSourcePixelsPerMinute: Double?,
    val medianBearingDegrees: Double?,
    val confidence: Double,
    val runtimeMillis: Long,
    /** Spatially disconnected vector supports retained in the dense field. */
    val spatialRegimes: Int = 0,
    /** Largest circular separation between robust local-cell median bearings. */
    val localBearingSeparationDegrees: Double? = null,
    val fallbackReason: String? = null,
)

data class NativeOpenRadarMotionResult(
    val field: RadarVelocityField?,
    val diagnostics: OpenRadarMotionDiagnostics,
)

data class OpenRadarHistoryPlan(
    val frames: List<TimedIntensityGrid>,
    val cadenceSeconds: Long,
)

object OpenRadarHistoryPolicy {
    private const val CADENCE_TOLERANCE_SECONDS = 5L

    fun latestRegular(
        frames: List<TimedIntensityGrid>,
        frameCount: Int = 3,
        expectedCadenceSeconds: Long? = null,
    ): OpenRadarHistoryPlan? {
        if (frameCount < 2 || frames.size < frameCount) return null
        val selected = frames.takeLast(frameCount)
        if (selected.any { it.grid.width != selected.last().grid.width ||
                it.grid.height != selected.last().grid.height }) return null
        val intervals = selected.zipWithNext { before, after ->
            after.epochSeconds - before.epochSeconds
        }
        if (intervals.any { it !in 60L..1_800L }) return null
        val cadence = expectedCadenceSeconds ?: intervals.sorted()[intervals.size / 2]
        if (intervals.any { abs(it - cadence) > CADENCE_TOLERANCE_SECONDS }) return null
        return OpenRadarHistoryPlan(selected, cadence)
    }
}

/**
 * Deterministic pySTEPS-style LK pipeline after feature tracking: pool adjacent-pair vectors,
 * robustly reject local outliers, decluster, then interpolate a locally supported dense IDW field.
 */
class NativeOpenRadarMotionEstimator(
    private val tracker: SparseRadarTracker,
) {
    fun estimate(
        frames: List<TimedIntensityGrid>,
        sourceWidth: Int,
        sourceHeight: Int,
        expectedCadenceSeconds: Long? = null,
        beforePair: () -> Unit = {},
    ): NativeOpenRadarMotionResult {
        val started = System.nanoTime()
        val plan = OpenRadarHistoryPolicy.latestRegular(
            frames,
            frameCount = REFERENCE_HISTORY_FRAMES,
            expectedCadenceSeconds = expectedCadenceSeconds,
        ) ?: return failed(
            "Three regular observations are required",
            expectedCadenceSeconds,
            0,
            started,
        )
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return failed("Invalid source raster size", plan.cadenceSeconds, plan.frames.size, started)
        }

        var detected = 0
        var trackerMillis = 0L
        val pooled = ArrayList<SparseRadarVector>()
        plan.frames.zipWithNext().forEach { (before, after) ->
            beforePair()
            val result = tracker.track(before.grid, after.grid)
            detected += result.detected
            trackerMillis += result.runtimeMillis
            pooled += result.accepted
        }
        val cleansed = OpenRadarVectorPolicy.clean(pooled)
        if (cleansed.size < MIN_ACCEPTED_VECTORS) {
            return failed(
                "Only ${cleansed.size} coherent vectors from $detected features",
                plan.cadenceSeconds,
                plan.frames.size,
                started,
                detected,
                cleansed.size,
            )
        }
        val analysisWidth = plan.frames.last().grid.width
        val analysisHeight = plan.frames.last().grid.height
        val scaleX = sourceWidth.toDouble() / analysisWidth
        val scaleY = sourceHeight.toDouble() / analysisHeight
        val regimes = OpenRadarVectorPolicy.regimes(cleansed)
        val dense = OpenRadarVectorPolicy.interpolate(
            cleansed,
            DENSE_FIELD_SIZE,
            DENSE_FIELD_SIZE,
            analysisWidth,
            analysisHeight,
            plan.cadenceSeconds,
        )
        val sourceVectors = dense.map { vector ->
            SparseRadarVector(vector.x, vector.y, vector.dx * scaleX, vector.dy * scaleY)
        }
        val magnitudes = sourceVectors.map { hypot(it.dx, it.dy) }.sorted()
        val p99 = magnitudes[((magnitudes.size - 1) * 0.99).roundToInt()]
        val encodingScale = OpenRadarVelocityEncoding.scaleFor(p99)
        val channels = ByteArray(DENSE_FIELD_SIZE * DENSE_FIELD_SIZE * 2)
        sourceVectors.forEachIndexed { index, vector ->
            channels[index * 2] = RadarVelocityField.encodeChannel(vector.dx, encodingScale)
            channels[index * 2 + 1] = RadarVelocityField.encodeChannel(vector.dy, encodingScale)
        }
        val sparseSourceDx = cleansed.map { it.dx * scaleX }
        val sparseSourceDy = cleansed.map { it.dy * scaleY }
        val medianDx = median(sparseSourceDx)
        val medianDy = median(sparseSourceDy)
        // Coherence is deliberately local. A smaller coherent storm moving differently from a
        // large rain band is evidence, not noise, and must not lower the whole-domain confidence.
        val spread = OpenRadarVectorPolicy.localResidualSpread(regimes)
        val support = (cleansed.size / TARGET_VECTOR_COUNT.toDouble()).coerceIn(0.0, 1.0)
        val coherence = (1.0 / (1.0 + spread / 3.0)).coerceIn(0.0, 1.0)
        val confidence = (0.55 * support + 0.45 * coherence).coerceIn(0.0, 1.0)
        if (confidence < MIN_CONFIDENCE) {
            return failed(
                "Vector confidence ${"%.2f".format(confidence)} is below threshold",
                plan.cadenceSeconds,
                plan.frames.size,
                started,
                detected,
                cleansed.size,
                confidence,
            )
        }
        val field = RadarVelocityField(
            width = DENSE_FIELD_SIZE,
            height = DENSE_FIELD_SIZE,
            channels = channels,
            maxDisplacementPixels = encodingScale,
            sourceIntervalSeconds = plan.cadenceSeconds,
            confidence = confidence,
        )
        return NativeOpenRadarMotionResult(
            field,
            OpenRadarMotionDiagnostics(
                activeLabel = "Native LK",
                cadenceSeconds = plan.cadenceSeconds,
                historyFrames = plan.frames.size,
                detectedVectors = detected,
                acceptedVectors = cleansed.size,
                medianSpeedSourcePixelsPerMinute = hypot(medianDx, medianDy) * 60.0 /
                    plan.cadenceSeconds,
                medianBearingDegrees = bearing(medianDx, medianDy),
                confidence = confidence,
                runtimeMillis = max(trackerMillis, elapsedMillis(started)),
                spatialRegimes = regimes.size,
                localBearingSeparationDegrees =
                    OpenRadarVectorPolicy.maximumLocalBearingSeparation(cleansed),
            ),
        )
    }

    private fun failed(
        reason: String,
        cadence: Long?,
        history: Int,
        started: Long,
        detected: Int = 0,
        accepted: Int = 0,
        confidence: Double = 0.0,
    ) = NativeOpenRadarMotionResult(
        field = null,
        diagnostics = OpenRadarMotionDiagnostics(
            activeLabel = "Current block matching (LK fallback)",
            cadenceSeconds = cadence,
            historyFrames = history,
            detectedVectors = detected,
            acceptedVectors = accepted,
            medianSpeedSourcePixelsPerMinute = null,
            medianBearingDegrees = null,
            confidence = confidence,
            runtimeMillis = elapsedMillis(started),
            fallbackReason = reason,
        ),
    )

    private fun elapsedMillis(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    companion object {
        const val REFERENCE_HISTORY_FRAMES = 3
        const val DENSE_FIELD_SIZE = 64
        const val MIN_ACCEPTED_VECTORS = 8
        private const val TARGET_VECTOR_COUNT = 60
        private const val MIN_CONFIDENCE = 0.18
    }
}

object OpenRadarFutureFieldSelector {
    fun select(
        legacyPairFields: List<RadarVelocityField?>,
        nativeFrames: List<TimedIntensityGrid>,
        sourceWidth: Int,
        sourceHeight: Int,
        expectedCadenceSeconds: Long?,
        trackerFactory: () -> SparseRadarTracker,
        beforePair: () -> Unit = {},
    ): DenseVelocitySet {
        val legacy = DenseRadarMotionEstimator.aggregate(legacyPairFields)
        val result = try {
            val estimator = NativeOpenRadarMotionEstimator(trackerFactory())
            val main = estimator.estimate(
                nativeFrames,
                sourceWidth,
                sourceHeight,
                expectedCadenceSeconds,
                beforePair,
            )
            main
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            NativeOpenRadarMotionResult(
                null,
                OpenRadarMotionDiagnostics(
                    activeLabel = "Current block matching (LK fallback)",
                    cadenceSeconds = expectedCadenceSeconds,
                    historyFrames = nativeFrames.takeLast(3).size,
                    detectedVectors = 0,
                    acceptedVectors = 0,
                    medianSpeedSourcePixelsPerMinute = null,
                    medianBearingDegrees = null,
                    confidence = 0.0,
                    runtimeMillis = 0,
                    fallbackReason = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".trim(),
                ),
            )
        }
        return DenseVelocitySet(
            pairFields = legacyPairFields,
            futureField = result.field ?: legacy,
            diagnostics = result.diagnostics,
        )
    }
}

object OpenRadarVectorPolicy {
    private const val LOCAL_SIGMA = 3.0
    private const val LOCAL_NEIGHBOURS = 30
    private const val MIN_LOCAL_NEIGHBOURS = 3
    private const val LOCAL_NEIGHBOUR_RADIUS = 64.0
    private const val MIN_COVARIANCE = 0.35 * 0.35
    private const val DECLUSTER_SCALE = 20.0
    private const val COMPONENT_LINK_DISTANCE = 56.0
    private const val MIN_SUPPORT_RADIUS = 24.0
    private const val MAX_SUPPORT_RADIUS = 48.0
    private const val IDW_NEIGHBOURS = 20
    private const val IDW_POWER = 0.5
    private const val IDW_OFFSET = 0.5
    private const val LOCAL_DIAGNOSTIC_SCALE = 48.0
    private const val DEFAULT_FORECAST_HORIZON_SECONDS = 3_600L

    fun clean(input: List<SparseRadarVector>): List<SparseRadarVector> {
        val finite = input.filter { listOf(it.x, it.y, it.dx, it.dy).all(Double::isFinite) }
        if (finite.size < NativeOpenRadarMotionEstimator.MIN_ACCEPTED_VECTORS) return emptyList()
        // Match pySTEPS' georeferenced/local intent: judge a vector against nearby motion, never
        // against a whole-domain direction. The radius also prevents a distant dominant band from
        // voting a coherent minority storm out of existence. Isolated tracks are rejected.
        val local = finite.filter { candidate ->
            val neighbours = finite.asSequence().filter { it !== candidate }
                .map { it to squaredDistance(it.x, it.y, candidate.x, candidate.y) }
                .filter { it.second <= LOCAL_NEIGHBOUR_RADIUS * LOCAL_NEIGHBOUR_RADIUS }
                .sortedBy { it.second }
                .take(LOCAL_NEIGHBOURS)
                .map { it.first }
                .toList()
            neighbours.size >= MIN_LOCAL_NEIGHBOURS &&
                withinLocalMultivariateEnvelope(candidate, neighbours)
        }
        return local.groupBy {
            (it.x / DECLUSTER_SCALE).toInt() to (it.y / DECLUSTER_SCALE).toInt()
        }.values.map { cluster ->
            SparseRadarVector(
                x = median(cluster.map { it.x }),
                y = median(cluster.map { it.y }),
                dx = median(cluster.map { it.dx }),
                dy = median(cluster.map { it.dy }),
                forwardBackwardError = median(cluster.map { it.forwardBackwardError }),
            )
        }
    }

    /** Local two-component Mahalanobis test, as used conceptually by pySTEPS for vector data. */
    private fun withinLocalMultivariateEnvelope(
        candidate: SparseRadarVector,
        reference: List<SparseRadarVector>,
    ): Boolean {
        val meanDx = reference.sumOf { it.dx } / reference.size
        val meanDy = reference.sumOf { it.dy } / reference.size
        val divisor = (reference.size - 1).coerceAtLeast(1).toDouble()
        var varianceDx = 0.0
        var varianceDy = 0.0
        var covariance = 0.0
        reference.forEach {
            val x = it.dx - meanDx
            val y = it.dy - meanDy
            varianceDx += x * x
            varianceDy += y * y
            covariance += x * y
        }
        varianceDx = varianceDx / divisor + MIN_COVARIANCE
        varianceDy = varianceDy / divisor + MIN_COVARIANCE
        covariance /= divisor
        val determinant = varianceDx * varianceDy - covariance * covariance
        if (!determinant.isFinite() || determinant <= 1e-9) return false
        val x = candidate.dx - meanDx
        val y = candidate.dy - meanDy
        val mahalanobisSquared =
            (x * x * varianceDy - 2.0 * x * y * covariance + y * y * varianceDx) /
                determinant
        return mahalanobisSquared.isFinite() &&
            mahalanobisSquared <= LOCAL_SIGMA * LOCAL_SIGMA
    }

    /** Spatial supports used for local confidence and component-bounded dense interpolation. */
    fun regimes(vectors: List<SparseRadarVector>): List<List<SparseRadarVector>> {
        if (vectors.isEmpty()) return emptyList()
        val parent = IntArray(vectors.size) { it }
        fun root(index: Int): Int {
            var value = index
            while (parent[value] != value) {
                parent[value] = parent[parent[value]]
                value = parent[value]
            }
            return value
        }
        fun union(a: Int, b: Int) {
            val first = root(a)
            val second = root(b)
            if (first != second) parent[second] = first
        }
        val linkSquared = COMPONENT_LINK_DISTANCE * COMPONENT_LINK_DISTANCE
        for (first in vectors.indices) for (second in first + 1 until vectors.size) {
            if (squaredDistance(
                    vectors[first].x, vectors[first].y,
                    vectors[second].x, vectors[second].y,
                ) <= linkSquared
            ) union(first, second)
        }
        return vectors.indices.groupBy(::root).values.map { indices -> indices.map(vectors::get) }
    }

    fun localResidualSpread(regimes: List<List<SparseRadarVector>>): Double {
        val residuals = regimes.flatMap { regime ->
            val medianDx = median(regime.map { it.dx })
            val medianDy = median(regime.map { it.dy })
            regime.map { hypot(it.dx - medianDx, it.dy - medianDy) }
        }
        return residuals.takeIf { it.isNotEmpty() }?.let(::median) ?: Double.POSITIVE_INFINITY
    }

    fun maximumRegimeBearingSeparation(regimes: List<List<SparseRadarVector>>): Double? {
        val bearings = regimes.mapNotNull { regime ->
            bearing(median(regime.map { it.dx }), median(regime.map { it.dy }))
        }
        if (bearings.size < 2) return null
        var maximum = 0.0
        for (first in bearings.indices) for (second in first + 1 until bearings.size) {
            var difference = abs(bearings[first] - bearings[second]) % 360.0
            if (difference > 180.0) difference = 360.0 - difference
            maximum = max(maximum, difference)
        }
        return maximum
    }

    fun maximumLocalBearingSeparation(vectors: List<SparseRadarVector>): Double? {
        val localGroups = vectors.groupBy {
            (it.x / LOCAL_DIAGNOSTIC_SCALE).toInt() to
                (it.y / LOCAL_DIAGNOSTIC_SCALE).toInt()
        }.values.filter { it.size >= 2 }
        return maximumRegimeBearingSeparation(localGroups)
    }

    fun interpolate(
        vectors: List<SparseRadarVector>,
        outputWidth: Int,
        outputHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceIntervalSeconds: Long = 300L,
        forecastHorizonSeconds: Long = DEFAULT_FORECAST_HORIZON_SECONDS,
    ): List<SparseRadarVector> {
        require(vectors.isNotEmpty() && outputWidth > 0 && outputHeight > 0 &&
            sourceIntervalSeconds > 0L && forecastHorizonSeconds >= 0L)
        val projectionSteps = forecastHorizonSeconds.toDouble() / sourceIntervalSeconds
        val supports = regimes(vectors).map { regime ->
            InterpolationSupport(
                vectors = regime,
                radius = supportRadius(regime),
            )
        }
        return buildList(outputWidth * outputHeight) {
            for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
                val sx = (x + 0.5) * sourceWidth / outputWidth
                val sy = (y + 0.5) * sourceHeight / outputHeight
                // The renderer uses backward warping, so velocity must exist along each system's
                // forward trajectory. Restrict it to that swept corridor instead of allowing a
                // low-power whole-domain IDW to blend unrelated storms across clear gaps.
                val supported = supports.map { support ->
                    val distances = support.vectors.map { vector ->
                        vector to distanceToSegment(
                            sx,
                            sy,
                            vector.x,
                            vector.y,
                            vector.x + vector.dx * projectionSteps,
                            vector.y + vector.dy * projectionSteps,
                        )
                    }
                    SupportedRegime(support, distances, distances.minOf { it.second })
                }.filter { it.nearestDistance <= it.support.radius }
                    .minByOrNull { it.nearestDistance / it.support.radius }
                if (supported == null) {
                    add(SparseRadarVector(sx, sy, 0.0, 0.0))
                    continue
                }
                val nearest = supported.distances.asSequence()
                    // Component membership alone is not local support: a chain of unrelated rain
                    // cells can connect a continent-scale component. Never let its distant
                    // dominant vectors fill a smaller system's IDW neighbourhood.
                    .filter { it.second <= supported.support.radius }
                    .sortedBy { it.second }
                    .take(IDW_NEIGHBOURS)
                    .toList()
                var totalWeight = 0.0
                var dx = 0.0
                var dy = 0.0
                nearest.forEach { (vector, distance) ->
                    val weight = 1.0 / (distance + IDW_OFFSET).pow(IDW_POWER)
                    totalWeight += weight
                    dx += vector.dx * weight
                    dy += vector.dy * weight
                }
                add(SparseRadarVector(sx, sy, dx / totalWeight, dy / totalWeight))
            }
        }
    }

    private data class InterpolationSupport(
        val vectors: List<SparseRadarVector>,
        val radius: Double,
    )

    private data class SupportedRegime(
        val support: InterpolationSupport,
        val distances: List<Pair<SparseRadarVector, Double>>,
        val nearestDistance: Double,
    )

    private fun supportRadius(vectors: List<SparseRadarVector>): Double {
        if (vectors.size < 2) return MIN_SUPPORT_RADIUS
        val nearestSpacing = vectors.mapIndexed { index, vector ->
            vectors.asSequence().filterIndexed { other, _ -> other != index }
                .minOf { other -> sqrt(squaredDistance(vector.x, vector.y, other.x, other.y)) }
        }
        return (median(nearestSpacing) * 2.0)
            .coerceIn(MIN_SUPPORT_RADIUS, MAX_SUPPORT_RADIUS)
    }

    private fun distanceToSegment(
        px: Double,
        py: Double,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
    ): Double {
        val segmentX = endX - startX
        val segmentY = endY - startY
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        if (lengthSquared <= 1e-9) return sqrt(squaredDistance(px, py, startX, startY))
        val fraction = (((px - startX) * segmentX + (py - startY) * segmentY) / lengthSquared)
            .coerceIn(0.0, 1.0)
        return sqrt(squaredDistance(
            px,
            py,
            startX + fraction * segmentX,
            startY + fraction * segmentY,
        ))
    }

    private fun squaredDistance(ax: Double, ay: Double, bx: Double, by: Double): Double =
        (ax - bx) * (ax - bx) + (ay - by) * (ay - by)
}

object OpenRadarVelocityEncoding {
    /** 5% headroom, rounded upward in 0.25px increments; keeps RG8 precision below 0.1px. */
    fun scaleFor(percentileMagnitude: Double): Float =
        (ceil(max(4.0, percentileMagnitude * 1.05) * 4.0) / 4.0)
            .coerceAtMost(96.0).toFloat()
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
}

private fun bearing(dx: Double, dy: Double): Double? =
    if (hypot(dx, dy) < 1e-6) null else (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
