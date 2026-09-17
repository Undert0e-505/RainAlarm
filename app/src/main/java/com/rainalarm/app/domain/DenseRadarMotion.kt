package com.rainalarm.app.domain

import kotlin.math.abs
import kotlin.math.roundToInt

data class RadarVelocityField(
    val width: Int,
    val height: Int,
    val channels: ByteArray,
    val maxDisplacementPixels: Float,
    val sourceIntervalSeconds: Long,
    val confidence: Double,
) {
    init {
        require(width > 0 && height > 0 && channels.size == width * height * 2)
        require(maxDisplacementPixels > 0f && maxDisplacementPixels.isFinite())
        require(sourceIntervalSeconds > 0)
        require(confidence in 0.0..1.0)
    }

    fun displacementAt(normalizedX: Double, normalizedY: Double): Pair<Double, Double> {
        val x = (normalizedX.coerceIn(0.0, 0.999999) * width).toInt()
        val y = (normalizedY.coerceIn(0.0, 0.999999) * height).toInt()
        val index = (y * width + x) * 2
        return decodeChannel(channels[index]) * maxDisplacementPixels to
            decodeChannel(channels[index + 1]) * maxDisplacementPixels
    }

    companion object {
        fun encodeChannel(displacement: Double, scale: Float): Byte =
            (((displacement / scale).coerceIn(-1.0, 1.0) * 0.5 + 0.5) * 255.0)
                .roundToInt().coerceIn(0, 255).toByte()

        fun decodeChannel(value: Byte): Double = ((value.toInt() and 0xff) / 255.0) * 2.0 - 1.0
    }
}

data class DenseVelocitySet(
    val pairFields: List<RadarVelocityField?>,
    val futureField: RadarVelocityField?,
)

object DenseRadarMotionEstimator {
    private const val CELLS = 8
    private const val BLOCK_RADIUS = 3
    private const val SEARCH_RADIUS = 5
    private const val MIN_VALID_CELLS = 4
    private const val MAX_SOURCE_DISPLACEMENT = 72f

    fun estimatePair(
        before: TimedIntensityGrid,
        after: TimedIntensityGrid,
        sourceWidth: Int,
        sourceHeight: Int,
    ): RadarVelocityField? {
        if (before.grid.width != after.grid.width || before.grid.height != after.grid.height) return null
        val interval = after.epochSeconds - before.epochSeconds
        if (interval !in 60..1_800 || sourceWidth <= 0 || sourceHeight <= 0) return null
        val vectors = arrayOfNulls<Vector>(CELLS * CELLS)
        val confidences = DoubleArray(CELLS * CELLS)
        for (cellY in 0 until CELLS) {
            for (cellX in 0 until CELLS) {
                val centerX = ((cellX + 0.5) * before.grid.width / CELLS).toInt()
                val centerY = ((cellY + 0.5) * before.grid.height / CELLS).toInt()
                val match = matchBlock(before.grid, after.grid, centerX, centerY) ?: continue
                val index = cellY * CELLS + cellX
                vectors[index] = Vector(match.dx.toDouble(), match.dy.toDouble())
                confidences[index] = match.confidence
            }
        }
        if (vectors.count { it != null } < MIN_VALID_CELLS) return null
        rejectOutliers(vectors)
        if (vectors.count { it != null } < MIN_VALID_CELLS) return null
        smoothAndFill(vectors)
        val scaleX = sourceWidth.toDouble() / before.grid.width
        val scaleY = sourceHeight.toDouble() / before.grid.height
        val channels = ByteArray(CELLS * CELLS * 2)
        vectors.forEachIndexed { index, vector ->
            val ready = requireNotNull(vector)
            channels[index * 2] = RadarVelocityField.encodeChannel(
                ready.dx * scaleX,
                MAX_SOURCE_DISPLACEMENT,
            )
            channels[index * 2 + 1] = RadarVelocityField.encodeChannel(
                ready.dy * scaleY,
                MAX_SOURCE_DISPLACEMENT,
            )
        }
        val confidence = confidences.filter { it > 0.0 }.average().coerceIn(0.0, 1.0)
        if (confidence < 0.10) return null
        return RadarVelocityField(CELLS, CELLS, channels, MAX_SOURCE_DISPLACEMENT, interval, confidence)
    }

    fun aggregate(fields: List<RadarVelocityField?>): RadarVelocityField? {
        val usable = fields.filterNotNull().takeLast(3)
        val first = usable.firstOrNull() ?: return null
        if (usable.any { it.width != first.width || it.height != first.height }) return null
        val channels = ByteArray(first.channels.size)
        for (cell in 0 until first.width * first.height) {
            val xs = usable.map { it.displacementAtCell(cell).first }.sorted()
            val ys = usable.map { it.displacementAtCell(cell).second }.sorted()
            channels[cell * 2] = RadarVelocityField.encodeChannel(xs[xs.size / 2], first.maxDisplacementPixels)
            channels[cell * 2 + 1] = RadarVelocityField.encodeChannel(ys[ys.size / 2], first.maxDisplacementPixels)
        }
        return RadarVelocityField(
            first.width,
            first.height,
            channels,
            first.maxDisplacementPixels,
            usable.map { it.sourceIntervalSeconds }.sorted()[usable.size / 2],
            usable.map { it.confidence }.average().coerceIn(0.0, 1.0),
        ).takeIf { it.confidence >= 0.18 }
    }

    private data class Match(val dx: Int, val dy: Int, val confidence: Double)
    private data class Vector(val dx: Double, val dy: Double)

    private fun matchBlock(before: IntensityGrid, after: IntensityGrid, cx: Int, cy: Int): Match? {
        var signal = 0
        var energy = 0.0
        for (y in cy - BLOCK_RADIUS..cy + BLOCK_RADIUS) {
            for (x in cx - BLOCK_RADIUS..cx + BLOCK_RADIUS) {
                if (x !in 0 until before.width || y !in 0 until before.height) continue
                val value = before[x, y].toDouble()
                if (value >= 0.08) signal++
                energy += value * value
            }
        }
        if (signal < 3 || energy < 0.08) return null
        var bestError = Double.POSITIVE_INFINITY
        var secondError = Double.POSITIVE_INFINITY
        var bestDx = 0
        var bestDy = 0
        for (dy in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                var error = 0.0
                var samples = 0
                for (y in cy - BLOCK_RADIUS..cy + BLOCK_RADIUS) {
                    for (x in cx - BLOCK_RADIUS..cx + BLOCK_RADIUS) {
                        val ax = x + dx
                        val ay = y + dy
                        if (x !in 0 until before.width || y !in 0 until before.height ||
                            ax !in 0 until after.width || ay !in 0 until after.height
                        ) continue
                        val delta = before[x, y] - after[ax, ay]
                        error += delta * delta
                        samples++
                    }
                }
                if (samples < 24) continue
                error /= samples
                if (error < bestError) {
                    secondError = bestError
                    bestError = error
                    bestDx = dx
                    bestDy = dy
                } else if (error < secondError) {
                    secondError = error
                }
            }
        }
        if (!bestError.isFinite() || bestError > 0.06 || !secondError.isFinite()) return null
        val uniqueness = ((secondError - bestError) / (secondError + 0.002)).coerceIn(0.0, 1.0)
        val fit = (1.0 - bestError / (energy / 49.0 + 0.01)).coerceIn(0.0, 1.0)
        val confidence = uniqueness * 0.45 + fit * 0.55
        return Match(bestDx, bestDy, confidence).takeIf { confidence >= 0.10 }
    }

    private fun rejectOutliers(vectors: Array<Vector?>) {
        val all = vectors.filterNotNull()
        val medianDx = all.map { it.dx }.sorted()[all.size / 2]
        val medianDy = all.map { it.dy }.sorted()[all.size / 2]
        vectors.indices.forEach { index ->
            val vector = vectors[index] ?: return@forEach
            val neighbours = neighbourVectors(vectors, index)
            val localDx = neighbours.map { it.dx }.sorted().let { it.getOrNull(it.size / 2) ?: medianDx }
            val localDy = neighbours.map { it.dy }.sorted().let { it.getOrNull(it.size / 2) ?: medianDy }
            if (abs(vector.dx - localDx) + abs(vector.dy - localDy) > 7.0 &&
                abs(vector.dx - medianDx) + abs(vector.dy - medianDy) > 7.0
            ) vectors[index] = null
        }
    }

    private fun smoothAndFill(vectors: Array<Vector?>) {
        val fallback = vectors.filterNotNull().let { values ->
            Vector(values.map { it.dx }.sorted()[values.size / 2], values.map { it.dy }.sorted()[values.size / 2])
        }
        val original = vectors.copyOf()
        vectors.indices.forEach { index ->
            val neighbours = neighbourVectors(original, index)
            if (neighbours.isEmpty()) {
                vectors[index] = original[index] ?: fallback
            } else {
                val values = neighbours + listOfNotNull(original[index])
                vectors[index] = Vector(
                    values.map { it.dx }.sorted()[values.size / 2],
                    values.map { it.dy }.sorted()[values.size / 2],
                )
            }
        }
    }

    private fun neighbourVectors(vectors: Array<Vector?>, index: Int): List<Vector> {
        val x = index % CELLS
        val y = index / CELLS
        val result = mutableListOf<Vector>()
        for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(CELLS - 1)) {
            for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(CELLS - 1)) {
                if (nx == x && ny == y) continue
                vectors[ny * CELLS + nx]?.let(result::add)
            }
        }
        return result
    }

    private fun RadarVelocityField.displacementAtCell(cell: Int): Pair<Double, Double> {
        val index = cell * 2
        return RadarVelocityField.decodeChannel(channels[index]) * maxDisplacementPixels to
            RadarVelocityField.decodeChannel(channels[index + 1]) * maxDisplacementPixels
    }
}

object DenseRadarAdvection {
    fun sourcePoint(
        normalizedX: Double,
        normalizedY: Double,
        minutes: Double,
        field: RadarVelocityField,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Double, Double> {
        var x = normalizedX * sourceWidth
        var y = normalizedY * sourceHeight
        var intervals = (minutes * 60.0 / field.sourceIntervalSeconds).coerceAtLeast(0.0)
        while (intervals > 0.0) {
            val step = minOf(1.0, intervals)
            val displacement = field.displacementAt(x / sourceWidth, y / sourceHeight)
            x -= displacement.first * step
            y -= displacement.second * step
            intervals -= step
        }
        return x to y
    }
}
