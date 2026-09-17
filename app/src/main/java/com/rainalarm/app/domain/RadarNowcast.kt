package com.rainalarm.app.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

data class GeoPoint(val latitude: Double, val longitude: Double)

data class GeoQuad(
    val topLeft: GeoPoint,
    val topRight: GeoPoint,
    val bottomRight: GeoPoint,
    val bottomLeft: GeoPoint,
)

object WebMercator {
    const val MAX_LATITUDE = 85.05112878
    private const val TILE_SIZE = 256.0

    fun imageBounds(
        center: GeoPoint,
        zoom: Int = 7,
        imageSize: Int = 512,
        dxPixels: Double = 0.0,
        dyPixels: Double = 0.0,
    ): GeoQuad {
        require(zoom in 0..22)
        require(imageSize in 1..4096)
        require(center.latitude in -MAX_LATITUDE..MAX_LATITUDE)
        require(center.longitude in -180.0..180.0)
        val world = TILE_SIZE * 2.0.pow(zoom)
        val (centerX, centerY) = project(center, zoom)
        val half = imageSize / 2.0
        val left = centerX + dxPixels - half
        val right = centerX + dxPixels + half
        val top = (centerY + dyPixels - half).coerceIn(0.0, world)
        val bottom = (centerY + dyPixels + half).coerceIn(0.0, world)
        return GeoQuad(
            topLeft = unproject(left, top, zoom),
            topRight = unproject(right, top, zoom),
            bottomRight = unproject(right, bottom, zoom),
            bottomLeft = unproject(left, bottom, zoom),
        )
    }

    fun displacedCenter(
        center: GeoPoint,
        zoom: Int,
        dxPixels: Double,
        dyPixels: Double,
    ): GeoPoint {
        val (x, y) = project(center, zoom)
        return unproject(x + dxPixels, y + dyPixels, zoom)
    }

    fun convertPixelDisplacement(
        dxPixels: Double,
        dyPixels: Double,
        fromZoom: Int,
        toZoom: Int,
    ): Pair<Double, Double> {
        require(fromZoom in 0..22 && toZoom in 0..22)
        val scale = 2.0.pow(toZoom - fromZoom)
        return dxPixels * scale to dyPixels * scale
    }

    private fun project(point: GeoPoint, zoom: Int): Pair<Double, Double> {
        val world = TILE_SIZE * 2.0.pow(zoom)
        val latitude = point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val sinLatitude = sin(Math.toRadians(latitude))
        val x = (point.longitude + 180.0) / 360.0 * world
        val y = (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI)) * world
        return x to y
    }

    private fun unproject(x: Double, y: Double, zoom: Int): GeoPoint {
        val world = TILE_SIZE * 2.0.pow(zoom)
        val longitude = x / world * 360.0 - 180.0
        val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / world))))
            .coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        return GeoPoint(latitude, longitude)
    }
}

data class IntensityGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray,
) {
    init {
        require(width > 0 && height > 0)
        require(values.size == width * height)
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    fun wetFractionAround(
        centerX: Double,
        centerY: Double,
        radius: Int = 3,
        threshold: Float = 0.16f,
    ): Double? {
        val startX = floor(centerX).toInt() - radius
        val startY = floor(centerY).toInt() - radius
        var total = 0
        var wet = 0
        for (y in startY..startY + radius * 2) {
            for (x in startX..startX + radius * 2) {
                if (x !in 0 until width || y !in 0 until height) continue
                total++
                if (get(x, y) >= threshold) wet++
            }
        }
        return if (total == 0) null else wet.toDouble() / total
    }
}

data class TimedIntensityGrid(val epochSeconds: Long, val grid: IntensityGrid)

data class MotionEstimate(
    val dxPixelsPerMinute: Double,
    val dyPixelsPerMinute: Double,
    val confidence: Double,
    val pairsUsed: Int,
) {
    val speedPixelsPerMinute: Double =
        kotlin.math.hypot(dxPixelsPerMinute, dyPixelsPerMinute)
    val bearingDegrees: Double =
        (Math.toDegrees(atan2(dxPixelsPerMinute, -dyPixelsPerMinute)) + 360.0) % 360.0
}

object RadarMotionEstimator {
    fun estimatePair(
        before: TimedIntensityGrid,
        after: TimedIntensityGrid,
        wetThreshold: Float = 0.16f,
        minimumWetPixels: Int = 16,
        maximumSpeedPixelsPerMinute: Double = 3.0,
    ): MotionEstimate? {
        val minutes = (after.epochSeconds - before.epochSeconds) / 60.0
        if (
            minutes !in 1.0..30.0 ||
            before.grid.width != after.grid.width ||
            before.grid.height != after.grid.height
        ) return null
        val scale = ceil(max(before.grid.width, before.grid.height) / 64.0)
            .toInt().coerceAtLeast(1)
        val matched = matchPair(
            downsample(before.grid, scale),
            downsample(after.grid, scale),
            minutes,
            wetThreshold,
            (minimumWetPixels / (scale * scale)).coerceAtLeast(4),
            maximumSpeedPixelsPerMinute / scale,
        ) ?: return null
        val dx = matched.dx * scale
        val dy = matched.dy * scale
        if (kotlin.math.hypot(dx, dy) > maximumSpeedPixelsPerMinute) return null
        return MotionEstimate(dx, dy, matched.confidence, 1)
    }

    fun estimate(
        frames: List<TimedIntensityGrid>,
        wetThreshold: Float = 0.16f,
        minimumWetPixels: Int = 16,
        maximumSpeedPixelsPerMinute: Double = 3.0,
    ): MotionEstimate? {
        if (frames.size < 2) return null
        val pairs = frames.zipWithNext().mapNotNull { (before, after) ->
            estimatePair(
                before,
                after,
                wetThreshold,
                minimumWetPixels,
                maximumSpeedPixelsPerMinute,
            )?.let { PairMotion(it.dxPixelsPerMinute, it.dyPixelsPerMinute, it.confidence) }
        }
        if (pairs.isEmpty()) return null
        val medianDx = median(pairs.map { it.dx })
        val medianDy = median(pairs.map { it.dy })
        val retained = pairs.filter {
            kotlin.math.hypot(it.dx - medianDx, it.dy - medianDy) <=
                max(0.35, maximumSpeedPixelsPerMinute * 0.35)
        }
        if (retained.isEmpty()) return null
        val dx = median(retained.map { it.dx })
        val dy = median(retained.map { it.dy })
        val speed = kotlin.math.hypot(dx, dy)
        val confidence = retained.map { it.confidence }.average() *
            (retained.size.toDouble() / pairs.size).coerceIn(0.5, 1.0)
        if (speed > maximumSpeedPixelsPerMinute || confidence < 0.42) return null
        return MotionEstimate(dx, dy, confidence.coerceIn(0.0, 1.0), retained.size)
    }

    private data class PairMotion(val dx: Double, val dy: Double, val confidence: Double)

    private fun matchPair(
        before: IntensityGrid,
        after: IntensityGrid,
        minutes: Double,
        threshold: Float,
        minimumWetPixels: Int,
        maximumSpeed: Double,
    ): PairMotion? {
        val beforeWet = before.values.count { it >= threshold }
        val afterWet = after.values.count { it >= threshold }
        if (beforeWet < minimumWetPixels || afterWet < minimumWetPixels) return null
        val maxShift = ceil(minutes * maximumSpeed).toInt()
            .coerceAtMost(min(before.width, before.height) / 3)
        var bestScore = -1.0
        var secondScore = -1.0
        var bestDx = 0
        var bestDy = 0
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var intersection = 0
                var union = 0
                val startX = max(0, -dx)
                val endX = min(before.width, before.width - dx)
                val startY = max(0, -dy)
                val endY = min(before.height, before.height - dy)
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val a = before[x, y] >= threshold
                        val b = after[x + dx, y + dy] >= threshold
                        if (a || b) union++
                        if (a && b) intersection++
                    }
                }
                if (union < minimumWetPixels) continue
                val score = intersection.toDouble() / union
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
        }
        val speed = kotlin.math.hypot(bestDx / minutes, bestDy / minutes)
        if (bestScore < 0.42 || speed > maximumSpeed) return null
        val uniqueness = (bestScore - secondScore.coerceAtLeast(0.0)).coerceIn(0.0, 0.2) / 0.2
        val confidence = (bestScore * 0.8 + uniqueness * 0.2).coerceIn(0.0, 1.0)
        return PairMotion(bestDx / minutes, bestDy / minutes, confidence)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun downsample(grid: IntensityGrid, scale: Int): IntensityGrid {
        if (scale == 1) return grid
        val width = ceil(grid.width / scale.toDouble()).toInt()
        val height = ceil(grid.height / scale.toDouble()).toInt()
        val values = FloatArray(width * height)
        for (targetY in 0 until height) {
            for (targetX in 0 until width) {
                var maximum = 0f
                for (sourceY in targetY * scale until min((targetY + 1) * scale, grid.height)) {
                    for (sourceX in targetX * scale until min((targetX + 1) * scale, grid.width)) {
                        maximum = max(maximum, grid[sourceX, sourceY])
                    }
                }
                values[targetY * width + targetX] = maximum
            }
        }
        return IntensityGrid(width, height, values)
    }
}

enum class RadarResolutionTier(val zoom: Int, val imageSize: Int = 512) {
    REGIONAL(5),
    DETAIL(7),
}

data class PhysicalRadarMotion(
    val dxWorldFractionPerMinute: Double,
    val dyWorldFractionPerMinute: Double,
    val confidence: Double,
    val pairsUsed: Int,
    val sourceTier: RadarResolutionTier,
) {
    val speedWorldFractionPerMinute: Double =
        kotlin.math.hypot(dxWorldFractionPerMinute, dyWorldFractionPerMinute)

    val bearingDegrees: Double =
        (Math.toDegrees(atan2(dxWorldFractionPerMinute, -dyWorldFractionPerMinute)) + 360.0) % 360.0

    fun pixelsPerMinute(tier: RadarResolutionTier): Pair<Double, Double> {
        val worldPixels = 256.0 * 2.0.pow(tier.zoom)
        return dxWorldFractionPerMinute * worldPixels to
            dyWorldFractionPerMinute * worldPixels
    }

    companion object {
        fun fromAnalysisPixels(
            estimate: MotionEstimate,
            tier: RadarResolutionTier,
            analysisWidth: Int,
            analysisHeight: Int,
        ): PhysicalRadarMotion {
            require(analysisWidth > 0 && analysisHeight > 0)
            val worldPixels = 256.0 * 2.0.pow(tier.zoom)
            return PhysicalRadarMotion(
                dxWorldFractionPerMinute = estimate.dxPixelsPerMinute *
                    (tier.imageSize.toDouble() / analysisWidth) / worldPixels,
                dyWorldFractionPerMinute = estimate.dyPixelsPerMinute *
                    (tier.imageSize.toDouble() / analysisHeight) / worldPixels,
                confidence = estimate.confidence,
                pairsUsed = estimate.pairsUsed,
                sourceTier = tier,
            )
        }
    }
}

object RadarMotionPolicy {
    const val MINIMUM_CONFIDENCE = 0.42
    private const val MINIMUM_REGIONAL_PIXELS_PER_MINUTE = 0.02

    fun usable(motion: PhysicalRadarMotion?): Boolean {
        if (motion == null) return false
        if (
            !motion.dxWorldFractionPerMinute.isFinite() ||
            !motion.dyWorldFractionPerMinute.isFinite() ||
            !motion.confidence.isFinite() ||
            motion.confidence < MINIMUM_CONFIDENCE
        ) return false
        val (dx, dy) = motion.pixelsPerMinute(RadarResolutionTier.REGIONAL)
        return kotlin.math.hypot(dx, dy) >= MINIMUM_REGIONAL_PIXELS_PER_MINUTE
    }

    fun preferred(
        regional: PhysicalRadarMotion?,
        detail: PhysicalRadarMotion?,
    ): PhysicalRadarMotion? = when {
        usable(regional) -> regional
        usable(detail) -> detail
        else -> null
    }
}

data class RadarOverlayFramePlan(
    val tier: RadarResolutionTier,
    val firstIndex: Int,
    val secondIndex: Int,
    val firstAlpha: Double,
    val secondAlpha: Double,
    val firstDxWorldFraction: Double,
    val firstDyWorldFraction: Double,
    val secondDxWorldFraction: Double,
    val secondDyWorldFraction: Double,
)

object RadarOverlayPlanner {
    const val DETAIL_CUTOFF_ZOOM = 6.35

    fun activeTier(
        mapZoom: Double,
        regionalAvailable: Boolean,
        detailAvailable: Boolean,
    ): RadarResolutionTier {
        require(regionalAvailable || detailAvailable)
        return if (mapZoom >= DETAIL_CUTOFF_ZOOM && detailAvailable) {
            RadarResolutionTier.DETAIL
        } else if (regionalAvailable) {
            RadarResolutionTier.REGIONAL
        } else {
            RadarResolutionTier.DETAIL
        }
    }

    fun plan(
        bracket: RadarTimelineBracket,
        tier: RadarResolutionTier,
        pairMotions: List<PhysicalRadarMotion?>,
        frameTimes: List<Long>,
        futureMotion: PhysicalRadarMotion?,
    ): RadarOverlayFramePlan {
        require(bracket.firstIndex in frameTimes.indices)
        require(bracket.secondIndex in frameTimes.indices)
        if (bracket.isForecast) {
            require(RadarMotionPolicy.usable(futureMotion))
            val motion = requireNotNull(futureMotion)
            val minutes = bracket.forecastMinutes.coerceIn(0.0, 60.0)
            return RadarOverlayFramePlan(
                tier = tier,
                firstIndex = frameTimes.lastIndex,
                secondIndex = frameTimes.lastIndex,
                firstAlpha = 1.0,
                secondAlpha = 0.0,
                firstDxWorldFraction = motion.dxWorldFractionPerMinute * minutes,
                firstDyWorldFraction = motion.dyWorldFractionPerMinute * minutes,
                secondDxWorldFraction = 0.0,
                secondDyWorldFraction = 0.0,
            )
        }
        val fraction = bracket.fraction.finiteOrZero().coerceIn(0.0, 1.0)
        val motion = if (bracket.secondIndex == bracket.firstIndex + 1) {
            pairMotions.getOrNull(bracket.firstIndex)?.takeIf(RadarMotionPolicy::usable)
        } else {
            null
        }
        val pairMinutes = if (motion == null) 0.0 else {
            (frameTimes[bracket.secondIndex] - frameTimes[bracket.firstIndex]) / 60.0
        }
        val dx = (motion?.dxWorldFractionPerMinute ?: 0.0) * pairMinutes
        val dy = (motion?.dyWorldFractionPerMinute ?: 0.0) * pairMinutes
        return RadarOverlayFramePlan(
            tier = tier,
            firstIndex = bracket.firstIndex,
            secondIndex = bracket.secondIndex,
            firstAlpha = if (bracket.firstIndex == bracket.secondIndex) 1.0 else 1.0 - fraction,
            secondAlpha = if (bracket.firstIndex == bracket.secondIndex) 0.0 else fraction,
            firstDxWorldFraction = dx * fraction,
            firstDyWorldFraction = dy * fraction,
            secondDxWorldFraction = -dx * (1.0 - fraction),
            secondDyWorldFraction = -dy * (1.0 - fraction),
        )
    }

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0
}

data class ScreenDisplacement(val dxPixels: Double, val dyPixels: Double)

data class RadarScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

object NorthUpRadarGeoreference {
    private const val MAPLIBRE_WORLD_SIZE_AT_ZOOM_ZERO = 512.0

    fun screenDisplacement(
        dxWorldFraction: Double,
        dyWorldFraction: Double,
        mapZoom: Double,
    ): ScreenDisplacement {
        require(mapZoom.isFinite())
        val scale = screenPixelsPerWorld(mapZoom)
        return ScreenDisplacement(dxWorldFraction * scale, dyWorldFraction * scale)
    }

    fun screenPixelsPerWorld(mapZoom: Double): Double {
        require(mapZoom.isFinite())
        return MAPLIBRE_WORLD_SIZE_AT_ZOOM_ZERO * 2.0.pow(mapZoom)
    }

    fun screenRect(
        bounds: GeoQuad,
        cameraCenter: GeoPoint,
        mapZoom: Double,
        viewportWidth: Int,
        viewportHeight: Int,
    ): RadarScreenRect {
        require(viewportWidth > 0 && viewportHeight > 0)
        require(mapZoom.isFinite())
        val (centerX, centerY) = normalizedWorld(cameraCenter)
        val (leftX, topY) = normalizedWorld(bounds.topLeft)
        val (rightX, bottomY) = normalizedWorld(bounds.bottomRight)
        val scale = screenPixelsPerWorld(mapZoom)
        return RadarScreenRect(
            left = viewportWidth / 2.0 + wrappedDelta(leftX, centerX) * scale,
            top = viewportHeight / 2.0 + (topY - centerY) * scale,
            right = viewportWidth / 2.0 + wrappedDelta(rightX, centerX) * scale,
            bottom = viewportHeight / 2.0 + (bottomY - centerY) * scale,
        )
    }

    private fun normalizedWorld(point: GeoPoint): Pair<Double, Double> {
        val latitude = point.latitude.coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE)
        val sinLatitude = sin(Math.toRadians(latitude))
        return (point.longitude + 180.0) / 360.0 to
            (0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI))
    }

    private fun wrappedDelta(value: Double, center: Double): Double {
        val delta = value - center
        return when {
            delta > 0.5 -> delta - 1.0
            delta < -0.5 -> delta + 1.0
            else -> delta
        }
    }
}

class RadarOverlayStateMachine {
    private var latest: RadarOverlayFramePlan? = null
    private var disposed = false

    val isDisposed: Boolean get() = disposed

    fun submit(plan: RadarOverlayFramePlan): Boolean {
        if (disposed) return false
        latest = plan
        return true
    }

    fun consumeLatest(): RadarOverlayFramePlan? {
        if (disposed) return null
        return latest.also { latest = null }
    }

    fun dispose() {
        disposed = true
        latest = null
    }
}

data class RadarTimelineBracket(
    val firstIndex: Int,
    val secondIndex: Int,
    val fraction: Double,
    val isForecast: Boolean,
    val forecastMinutes: Double,
)

object RadarTimeline {
    const val FORECAST_HORIZON_SECONDS = 60 * 60L

    fun endOffsetSeconds(
        frameTimes: List<Long>,
        futureMotion: PhysicalRadarMotion?,
    ): Long {
        require(frameTimes.isNotEmpty())
        require(frameTimes.zipWithNext().all { (a, b) -> b > a })
        return frameTimes.last() - frameTimes.first() +
            if (RadarMotionPolicy.usable(futureMotion)) FORECAST_HORIZON_SECONDS else 0L
    }

    fun bracket(frameTimes: List<Long>, cursorEpochSeconds: Double): RadarTimelineBracket {
        require(frameTimes.isNotEmpty())
        require(frameTimes.zipWithNext().all { (a, b) -> b > a })
        val latest = frameTimes.last()
        val cursor = if (cursorEpochSeconds.isFinite()) {
            cursorEpochSeconds.coerceIn(
                frameTimes.first().toDouble(),
                latest + FORECAST_HORIZON_SECONDS.toDouble(),
            )
        } else {
            latest.toDouble()
        }
        if (cursor >= latest) {
            return RadarTimelineBracket(
                firstIndex = frameTimes.lastIndex,
                secondIndex = frameTimes.lastIndex,
                fraction = 0.0,
                isForecast = cursor > latest,
                forecastMinutes = ((cursor - latest) / 60.0).coerceIn(0.0, 60.0),
            )
        }
        if (cursor <= frameTimes.first()) {
            return RadarTimelineBracket(0, 0, 0.0, false, 0.0)
        }
        val second = frameTimes.indexOfFirst { it >= cursor }.coerceAtLeast(1)
        val first = second - 1
        val span = (frameTimes[second] - frameTimes[first]).toDouble()
        return RadarTimelineBracket(
            firstIndex = first,
            secondIndex = second,
            fraction = ((cursor - frameTimes[first]) / span).coerceIn(0.0, 1.0),
            isForecast = false,
            forecastMinutes = 0.0,
        )
    }

    fun bracket(
        frameTimes: List<Long>,
        forecastFlags: List<Boolean>,
        cursorEpochSeconds: Double,
    ): RadarTimelineBracket {
        require(frameTimes.size == forecastFlags.size && frameTimes.isNotEmpty())
        require(frameTimes.zipWithNext().all { (a, b) -> b > a })
        val cursor = if (cursorEpochSeconds.isFinite()) {
            cursorEpochSeconds.coerceIn(frameTimes.first().toDouble(), frameTimes.last().toDouble())
        } else {
            frameTimes.last().toDouble()
        }
        val latestObservationIndex = forecastFlags.indexOfLast { !it }.coerceAtLeast(0)
        val latestObservation = frameTimes[latestObservationIndex]
        if (cursor <= frameTimes.first()) {
            return RadarTimelineBracket(0, 0, 0.0, forecastFlags.first(), 0.0)
        }
        val exact = frameTimes.indexOfFirst { it.toDouble() == cursor }
        if (exact >= 0) {
            return RadarTimelineBracket(
                exact,
                exact,
                0.0,
                forecastFlags[exact],
                ((cursor - latestObservation) / 60.0).coerceAtLeast(0.0),
            )
        }
        val second = frameTimes.indexOfFirst { it > cursor }.let { if (it < 0) frameTimes.lastIndex else it }
        val first = (second - 1).coerceAtLeast(0)
        val span = (frameTimes[second] - frameTimes[first]).toDouble().coerceAtLeast(1.0)
        return RadarTimelineBracket(
            first,
            second,
            ((cursor - frameTimes[first]) / span).coerceIn(0.0, 1.0),
            forecastFlags[first] || forecastFlags[second] || cursor > latestObservation,
            ((cursor - latestObservation) / 60.0).coerceAtLeast(0.0),
        )
    }
}

fun compassDirection(bearing: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((bearing + 22.5) / 45.0).toInt() % names.size]
}
