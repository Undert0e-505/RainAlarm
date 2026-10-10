package com.rainalarm.app.domain

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot
import kotlin.math.ln1p

object OpenCvRuntime {
    @Volatile
    private var state: Boolean? = null

    @Volatile
    var failureMessage: String? = null
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        state?.let { return it }
        return try {
            val loaded = OpenCVLoader.initLocal()
            state = loaded
            failureMessage = if (loaded) null else "OpenCVLoader.initLocal returned false"
            Log.i(TAG, "OpenCV ${Core.VERSION} loaded=$loaded")
            loaded
        } catch (failure: Throwable) {
            state = false
            failureMessage = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}".trim()
            Log.e(TAG, "OpenCV initialization failed", failure)
            false
        }
    }

    private const val TAG = "RainRadarNativeMotion"
}

/**
 * OpenCV implementation of the pySTEPS 1.21.5 LK reference settings. The extra backward pass
 * rejects inconsistent tracks; pySTEPS' subsequent outlier/declustering/IDW stages live in the
 * pure Kotlin estimator.
 */
class OpenCvSparseRadarTracker : SparseRadarTracker {
    override fun track(before: IntensityGrid, after: IntensityGrid): SparseRadarTrackResult {
        require(before.width == after.width && before.height == after.height)
        val started = System.nanoTime()
        if (!OpenCvRuntime.ensureLoaded()) {
            throw IllegalStateException(OpenCvRuntime.failureMessage ?: "OpenCV is unavailable")
        }
        val owned = ArrayList<Mat>(16)
        fun <T : Mat> own(value: T): T = value.also { owned += it }
        try {
            val previous = own(toFeatureImage(before))
            val next = own(toFeatureImage(after))
            val mask = own(featureMask(before))
            // A single domain-wide Shi-Tomasi ranking allows a broad/high-contrast band to use
            // the entire corner quota. Run the same reference detector independently in bounded
            // spatial tiles so a weaker, smaller storm can still contribute valid local motion.
            val detectedPoints = detectSpatiallyBalancedFeatures(previous, mask)
            val detected = detectedPoints.size
            if (detected == 0) return result(0, emptyList(), started)
            val previousPoints = own(MatOfPoint2f(*detectedPoints.toTypedArray()))
            val nextPoints = own(MatOfPoint2f())
            val forwardStatus = own(MatOfByte())
            val forwardError = own(MatOfFloat())
            Video.calcOpticalFlowPyrLK(
                previous,
                next,
                previousPoints,
                nextPoints,
                forwardStatus,
                forwardError,
                Size(WINDOW_SIZE, WINDOW_SIZE),
                MAX_PYRAMID_LEVEL,
                TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 10, 0.01),
                0,
                MIN_EIGEN_THRESHOLD,
            )
            val backPoints = own(MatOfPoint2f())
            val backwardStatus = own(MatOfByte())
            val backwardError = own(MatOfFloat())
            Video.calcOpticalFlowPyrLK(
                next,
                previous,
                nextPoints,
                backPoints,
                backwardStatus,
                backwardError,
                Size(WINDOW_SIZE, WINDOW_SIZE),
                MAX_PYRAMID_LEVEL,
                TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 10, 0.01),
                0,
                MIN_EIGEN_THRESHOLD,
            )
            val source = previousPoints.toArray()
            val destination = nextPoints.toArray()
            val returned = backPoints.toArray()
            val forward = forwardStatus.toArray()
            val backward = backwardStatus.toArray()
            val accepted = buildList {
                for (index in source.indices) {
                    if (forward.getOrNull(index)?.toInt() != 1 ||
                        backward.getOrNull(index)?.toInt() != 1) continue
                    val from = source[index]
                    val to = destination.getOrNull(index) ?: continue
                    val back = returned.getOrNull(index) ?: continue
                    if (!insideBuffered(to, before.width, before.height)) continue
                    val fbError = hypot(from.x - back.x, from.y - back.y)
                    val dx = to.x - from.x
                    val dy = to.y - from.y
                    if (fbError > MAX_FORWARD_BACKWARD_ERROR ||
                        hypot(dx, dy) > MAX_TRACK_DISPLACEMENT) continue
                    add(SparseRadarVector(to.x, to.y, dx, dy, fbError))
                }
            }
            return result(detected, accepted, started, detectedPoints.map {
                SparseRadarPoint(it.x, it.y)
            })
        } finally {
            owned.asReversed().forEach(Mat::release)
        }
    }

    private fun toFeatureImage(grid: IntensityGrid): Mat {
        val bytes = ByteArray(grid.values.size) { index ->
            val value = grid.values[index]
            val transformed = if (!value.isFinite() || value < PRECIPITATION_THRESHOLD) 0.0
            else ln1p(LOG_SCALE * value.coerceIn(0f, 1f)) / ln1p(LOG_SCALE)
            (transformed * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        val source = Mat(grid.height, grid.width, CvType.CV_8UC1)
        source.put(0, 0, bytes)
        val opened = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        return try {
            Imgproc.morphologyEx(source, opened, Imgproc.MORPH_OPEN, kernel)
            opened
        } finally {
            source.release()
            kernel.release()
        }
    }

    private fun featureMask(grid: IntensityGrid): Mat {
        val bytes = ByteArray(grid.values.size) { index ->
            val x = index % grid.width
            val y = index / grid.width
            if (x < MASK_BUFFER || y < MASK_BUFFER ||
                x >= grid.width - MASK_BUFFER || y >= grid.height - MASK_BUFFER ||
                !grid.values[index].isFinite() || grid.values[index] < PRECIPITATION_THRESHOLD
            ) 0 else 0xff.toByte()
        }
        return Mat(grid.height, grid.width, CvType.CV_8UC1).also { it.put(0, 0, bytes) }
    }

    private fun detectSpatiallyBalancedFeatures(image: Mat, mask: Mat): List<Point> = buildList {
        for (top in 0 until image.rows() step FEATURE_TILE_SIZE) {
            for (left in 0 until image.cols() step FEATURE_TILE_SIZE) {
                val rectangle = Rect(
                    left,
                    top,
                    minOf(FEATURE_TILE_SIZE, image.cols() - left),
                    minOf(FEATURE_TILE_SIZE, image.rows() - top),
                )
                val imageTile = image.submat(rectangle)
                val maskTile = mask.submat(rectangle)
                val corners = MatOfPoint()
                try {
                    Imgproc.goodFeaturesToTrack(
                        imageTile,
                        corners,
                        MAX_CORNERS_PER_TILE,
                        QUALITY_LEVEL,
                        MIN_DISTANCE,
                        maskTile,
                        BLOCK_SIZE,
                        false,
                        HARRIS_K,
                    )
                    corners.toArray().forEach { point ->
                        add(Point(point.x + left, point.y + top))
                    }
                } finally {
                    corners.release()
                    maskTile.release()
                    imageTile.release()
                }
            }
        }
    }

    private fun insideBuffered(point: Point, width: Int, height: Int): Boolean =
        point.x >= MASK_BUFFER && point.y >= MASK_BUFFER &&
            point.x < width - MASK_BUFFER && point.y < height - MASK_BUFFER

    private fun result(
        detected: Int,
        accepted: List<SparseRadarVector>,
        started: Long,
        detectedPoints: List<SparseRadarPoint> = emptyList(),
    ) = SparseRadarTrackResult(
        detected,
        accepted,
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L),
        detectedPoints,
    )

    private companion object {
        const val FEATURE_TILE_SIZE = 64
        const val MAX_CORNERS_PER_TILE = 64
        const val QUALITY_LEVEL = 0.01
        const val MIN_DISTANCE = 10.0
        const val BLOCK_SIZE = 5
        const val HARRIS_K = 0.04
        const val WINDOW_SIZE = 50.0
        const val MAX_PYRAMID_LEVEL = 3
        const val MIN_EIGEN_THRESHOLD = 1e-4
        const val MASK_BUFFER = 5
        const val MAX_FORWARD_BACKWARD_ERROR = 1.5
        const val MAX_TRACK_DISPLACEMENT = 72.0
        const val PRECIPITATION_THRESHOLD = 0.03f
        const val LOG_SCALE = 31.0
    }
}
