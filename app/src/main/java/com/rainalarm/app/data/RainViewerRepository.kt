package com.rainalarm.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.OpenRadarColorScale
import com.rainalarm.app.domain.PhysicalRadarMotion
import com.rainalarm.app.domain.RadarMotionEstimator
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.TimedIntensityGrid
import com.rainalarm.app.domain.WebMercator
import com.rainalarm.app.domain.DenseRadarMotionEstimator
import com.rainalarm.app.domain.DenseVelocitySet
import com.rainalarm.app.domain.RadarVelocityField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

const val RADAR_IMAGE_ZOOM = 7
const val RADAR_IMAGE_SIZE = 512
private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
private const val MOTION_ANALYSIS_SIZE = 64

data class RainViewerRasterPlacement(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class RainViewerRasterSegment(
    val center: GeoPoint,
    val sourceBounds: GeoQuad,
    val destination: RainViewerRasterPlacement,
)

data class RainViewerRasterPlan(
    val zoom: Int,
    val imageSize: Int,
    val targetBounds: GeoQuad,
    val outputWidth: Int,
    val outputHeight: Int,
    val segments: List<RainViewerRasterSegment>,
    val regionalAreaId: String? = null,
)

object RainViewerRasterPlanner {
    private const val MAX_ZOOM = 7
    private const val MAX_REGIONAL_SEGMENTS = 2

    fun forTier(place: SavedPlace, tier: RadarResolutionTier): RainViewerRasterPlan {
        val area = if (tier == RadarResolutionTier.REGIONAL) {
            RegionalRadarAreas.forPoint(place.latitude, place.longitude)
        } else null
        return area?.let(::forRegionalArea) ?: forSelectedPlace(place, tier)
    }

    fun forRegionalArea(area: RegionalRadarArea): RainViewerRasterPlan {
        val target = area.bounds
        val (left, top) = WebMercator.worldFraction(target.topLeft)
        val (right, bottom) = WebMercator.worldFraction(target.bottomRight)
        val width = right - left
        val height = bottom - top
        require(width > 0.0 && height > 0.0)
        // One coordinate response covers one standard tile at its requested zoom. Allow at most
        // two overlapping windows so large UK/France footprints retain useful ~z4 resolution
        // rather than dropping to a single, visibly coarse z3 image.
        val zoom = (MAX_ZOOM downTo 0).first { candidate ->
            val span = 1.0 / (1 shl candidate)
            val columns = kotlin.math.ceil(width / span).toInt().coerceAtLeast(1)
            val rows = kotlin.math.ceil(height / span).toInt().coerceAtLeast(1)
            columns * rows <= MAX_REGIONAL_SEGMENTS
        }
        return build(target, zoom, area.id)
    }

    private fun forSelectedPlace(place: SavedPlace, tier: RadarResolutionTier): RainViewerRasterPlan {
        val center = GeoPoint(place.latitude, place.longitude)
        val bounds = WebMercator.coordinateImageBounds(center, tier.zoom, RADAR_IMAGE_SIZE)
        return RainViewerRasterPlan(
            zoom = tier.zoom,
            imageSize = RADAR_IMAGE_SIZE,
            targetBounds = bounds,
            outputWidth = RADAR_IMAGE_SIZE,
            outputHeight = RADAR_IMAGE_SIZE,
            segments = listOf(
                RainViewerRasterSegment(
                    center,
                    bounds,
                    RainViewerRasterPlacement(0.0, 0.0, RADAR_IMAGE_SIZE.toDouble(), RADAR_IMAGE_SIZE.toDouble()),
                ),
            ),
        )
    }

    private fun build(
        targetBounds: GeoQuad,
        zoom: Int,
        regionalAreaId: String?,
    ): RainViewerRasterPlan {
        val (targetLeft, targetTop) = WebMercator.worldFraction(targetBounds.topLeft)
        val (targetRight, targetBottom) = WebMercator.worldFraction(targetBounds.bottomRight)
        val targetWidth = targetRight - targetLeft
        val targetHeight = targetBottom - targetTop
        val sourceSpan = 1.0 / (1 shl zoom)
        val columns = kotlin.math.ceil(targetWidth / sourceSpan).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(targetHeight / sourceSpan).toInt().coerceAtLeast(1)
        require(columns * rows <= MAX_REGIONAL_SEGMENTS)
        val outputWidth = (targetWidth * RADAR_IMAGE_SIZE * (1 shl zoom)).roundToInt().coerceAtLeast(1)
        val outputHeight = (targetHeight * RADAR_IMAGE_SIZE * (1 shl zoom)).roundToInt().coerceAtLeast(1)
        val edgeGuard = sourceSpan / RADAR_IMAGE_SIZE

        fun centers(minimum: Double, maximum: Double, count: Int): List<Double> = when (count) {
            1 -> listOf((minimum + maximum) / 2.0)
            else -> List(count) { index ->
                minimum + sourceSpan / 2.0 - edgeGuard +
                    (maximum - minimum - sourceSpan + edgeGuard * 2.0) *
                    index / (count - 1).toDouble()
            }
        }
        val segments = centers(targetTop, targetBottom, rows).flatMap { centerY ->
            centers(targetLeft, targetRight, columns).map { centerX ->
                val center = WebMercator.pointAtWorldFraction(centerX, centerY)
                val sourceBounds = WebMercator.coordinateImageBounds(center, zoom, RADAR_IMAGE_SIZE)
                val (sourceLeft, sourceTop) = WebMercator.worldFraction(sourceBounds.topLeft)
                val (sourceRight, sourceBottom) = WebMercator.worldFraction(sourceBounds.bottomRight)
                RainViewerRasterSegment(
                    center,
                    sourceBounds,
                    RainViewerRasterPlacement(
                        (sourceLeft - targetLeft) / targetWidth * outputWidth,
                        (sourceTop - targetTop) / targetHeight * outputHeight,
                        (sourceRight - targetLeft) / targetWidth * outputWidth,
                        (sourceBottom - targetTop) / targetHeight * outputHeight,
                    ),
                )
            }
        }
        return RainViewerRasterPlan(
            zoom = zoom,
            imageSize = RADAR_IMAGE_SIZE,
            targetBounds = targetBounds,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            segments = segments,
            regionalAreaId = regionalAreaId,
        )
    }
}

@Serializable
data class RainViewerFrame(
    val time: Long,
    val path: String,
    val forecast: Boolean = false,
)

@Serializable
private data class RainViewerRadar(val past: List<RainViewerFrame> = emptyList())

@Serializable
private data class RainViewerManifest(
    val host: String = "https://tilecache.rainviewer.com",
    val radar: RainViewerRadar = RainViewerRadar(),
)

data class RadarManifest(val host: String, val frames: List<RainViewerFrame>)

object RainViewerManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, now: Instant = Instant.now()): RadarManifest {
        val manifest = json.decodeFromString<RainViewerManifest>(body)
        val host = URL(manifest.host)
        require(host.protocol == "https" && host.host.isNotBlank()) {
            "Radar tile host must use HTTPS"
        }
        val cutoff = now.minusSeconds(2 * 60 * 60L).epochSecond
        val frames = manifest.radar.past
            .filter { it.time in cutoff..now.epochSecond }
            .filter { it.path.startsWith("/v2/radar/") }
            .sortedBy { it.time }
            .distinctBy { it.time }
        require(frames.size >= 2) { "Radar history has fewer than two usable frames" }
        return RadarManifest(manifest.host.removeSuffix("/"), frames)
    }
}

fun buildCoordinateRadarUrl(
    host: String,
    frame: RainViewerFrame,
    place: SavedPlace,
    zoom: Int = RADAR_IMAGE_ZOOM,
    imageSize: Int = RADAR_IMAGE_SIZE,
    showLikelySnow: Boolean = false,
): String = buildCoordinateRadarUrl(
    host,
    frame,
    GeoPoint(place.latitude, place.longitude),
    zoom,
    imageSize,
    showLikelySnow,
)

fun buildCoordinateRadarUrl(
    host: String,
    frame: RainViewerFrame,
    center: GeoPoint,
    zoom: Int = RADAR_IMAGE_ZOOM,
    imageSize: Int = RADAR_IMAGE_SIZE,
    showLikelySnow: Boolean = false,
): String {
    val hostUrl = URL(host)
    require(hostUrl.protocol == "https" && hostUrl.host.isNotBlank()) {
        "Radar tile host must use HTTPS"
    }
    require(frame.path.startsWith("/v2/radar/")) { "Invalid radar frame path" }
    require(zoom in 0..7) { "Radar image zoom must be between 0 and 7" }
    require(imageSize == 256 || imageSize == 512) { "Radar image size must be 256 or 512" }
    return "${host.removeSuffix("/")}${frame.path}/$imageSize/$zoom/" +
        "${center.latitude}/${center.longitude}/2/1_${if (showLikelySnow) 1 else 0}.png"
}

fun buildCoordinateCoverageUrl(
    host: String,
    place: SavedPlace,
    zoom: Int = RADAR_IMAGE_ZOOM,
    imageSize: Int = RADAR_IMAGE_SIZE,
): String {
    val hostUrl = URL(host)
    require(hostUrl.protocol == "https" && hostUrl.host.isNotBlank()) {
        "Radar tile host must use HTTPS"
    }
    require(zoom in 0..7) { "Radar image zoom must be between 0 and 7" }
    require(imageSize == 256 || imageSize == 512) { "Radar image size must be 256 or 512" }
    return "${host.removeSuffix("/")}/v2/coverage/0/$imageSize/$zoom/" +
        "${place.latitude}/${place.longitude}/0/0_0.png"
}

fun buildCoordinateCoverageUrl(
    host: String,
    center: GeoPoint,
    zoom: Int = RADAR_IMAGE_ZOOM,
    imageSize: Int = RADAR_IMAGE_SIZE,
): String = buildCoordinateCoverageUrl(
    host,
    SavedPlace("coverage", center.latitude, center.longitude, id = "coverage"),
    zoom,
    imageSize,
)

/** Small process cache: RainViewer documents this mask as changing infrequently. */
private object RainViewerCoverageCache {
    private const val MAX_ENTRIES = 16
    private val mutex = Mutex()
    private val encoded = object : LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true) {}

    suspend fun get(url: String, fetch: suspend () -> ByteArray): ByteArray = mutex.withLock {
        encoded[url]?.let { return@withLock it }
        val value = fetch()
        encoded[url] = value
        while (encoded.size > MAX_ENTRIES) encoded.entries.iterator().let { iterator ->
            iterator.next()
            iterator.remove()
        }
        value
    }
}

enum class RadarLoadMode { SCREEN_TWO_TIER, ALERT_ANALYSIS }

data class RadarFrameRequest(
    val frame: RainViewerFrame,
    val tier: RadarResolutionTier,
)

fun radarRequestPlan(
    frames: List<RainViewerFrame>,
    mode: RadarLoadMode,
): List<RadarFrameRequest> = when (mode) {
    RadarLoadMode.SCREEN_TWO_TIER, RadarLoadMode.ALERT_ANALYSIS ->
        RadarResolutionTier.entries.flatMap { tier -> frames.map { RadarFrameRequest(it, tier) } }
}

interface RadarEndpoint {
    suspend fun fetchManifest(): String
    suspend fun fetchImage(url: String): ByteArray
}

class RainViewerEndpoint(
    private val manifestUrl: String = "https://api.rainviewer.com/public/weather-maps.json",
) : RadarEndpoint {
    override suspend fun fetchManifest(): String = withContext(Dispatchers.IO) {
        val url = URL(manifestUrl)
        require(url.protocol == "https") { "Radar endpoint must use HTTPS" }
        val connection = open(url)
        try {
            require(connection.contentType?.startsWith("application/json") == true) {
                "Radar manifest was not JSON"
            }
            connection.inputStream.bufferedReader().use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(4 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer)
                    if (read < 0) break
                    require(body.length + read <= 256 * 1024) { "Radar manifest is too large" }
                    body.append(buffer, 0, read)
                }
                currentCoroutineContext().ensureActive()
                body.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun fetchImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Radar image endpoint must use HTTPS" }
        val connection = open(parsed)
        try {
            require(connection.contentType?.startsWith("image/png") == true) {
                "Radar frame was not a PNG image"
            }
            val advertised = connection.contentLengthLong
            require(advertised in -1..MAX_IMAGE_BYTES.toLong()) { "Radar frame is too large" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_IMAGE_BYTES) { "Radar frame is too large" }
                    output.write(buffer, 0, count)
                }
                currentCoroutineContext().ensureActive()
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/json,image/png")
        connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
        val response = connection.responseCode
        if (response == 429 || response in 500..599) {
            connection.disconnect()
            throw java.io.IOException("Radar service is temporarily unavailable")
        }
        if (response !in 200..299) {
            connection.disconnect()
            error("Radar service returned $response")
        }
        return connection
    }
}

data class RadarBitmapFrame(
    val frame: RainViewerFrame,
    val bitmap: Bitmap,
    val velocityBitmap: Bitmap? = null,
)

data class RadarTierFrames(
    val tier: RadarResolutionTier,
    val bounds: GeoQuad,
    val frames: List<RadarBitmapFrame>,
) {
    val frameTimes: List<Long> = frames.map { it.frame.time }
}

/** Compact regional frame retained for the screen session and EGL context recreation. */
data class LegacyCompressedFrame(
    val id: Int,
    val frame: RainViewerFrame,
    val radarJpeg: ByteArray,
    val velocityJpeg: ByteArray?,
)

/** Provider-published/current unknown-area raster. Transparent pixels are known coverage. */
data class RadarCoverageRaster(
    val bounds: GeoQuad,
    val bitmap: Bitmap,
    val semantics: RadarCoverageSemantics,
)

fun RadarCoverageRaster.coverageAt(point: GeoPoint): Boolean? {
    if (bitmap.isRecycled) return null
    val (left, top) = WebMercator.worldFraction(bounds.topLeft)
    val (right, bottom) = WebMercator.worldFraction(bounds.bottomRight)
    val (x, y) = WebMercator.worldFraction(point)
    if (x !in left..right || y !in top..bottom) return false
    val pixelX = (((x - left) / (right - left)) * bitmap.width).toInt()
        .coerceIn(0, bitmap.width - 1)
    val pixelY = (((y - top) / (bottom - top)) * bitmap.height).toInt()
        .coerceIn(0, bitmap.height - 1)
    val alpha = (bitmap.getPixel(pixelX, pixelY) ushr 24) and 0xff
    return alpha < 128
}

enum class RadarCoverageSemantics {
    NOMINAL,
    CURRENT_COMPOSITE,
    PROVIDER_PUBLISHED,
}

data class RadarPointSample(
    val time: Long,
    val forecast: Boolean,
    val intensity: Float,
    val minimum: Float = intensity,
    val maximum: Float = intensity,
    val localVelocityX: Float? = null,
    val localVelocityY: Float? = null,
    /** Selected-place source-space pixels only; no full regional intensity grid is retained. */
    val regionalPatch: com.rainalarm.app.domain.RegionalPointPatch? = null,
) {
    init {
        require(minimum in 0f..1f && intensity in 0f..1f && maximum in 0f..1f)
        require(minimum <= intensity && intensity <= maximum)
    }

    val travelBearingDegrees: Double?
        get() = if (localVelocityX == null || localVelocityY == null) null else
            com.rainalarm.app.domain.bearingForVector(localVelocityX.toDouble(), localVelocityY.toDouble())
}

data class LegacyRadarArchive(
    val frames: List<LegacyCompressedFrame>,
    val pointSamples: List<RadarPointSample>,
    val totalCompressedBytes: Long,
) {
    init {
        require(frames.size >= 2)
        require(frames.map { it.frame.time }.zipWithNext().all { (a, b) -> b > a })
        require(pointSamples.map { it.time } == frames.map { it.frame.time })
        require(totalCompressedBytes == frames.sumOf { it.radarJpeg.size.toLong() + (it.velocityJpeg?.size ?: 0) })
    }
}

data class RadarSession(
    val place: SavedPlace,
    val regional: RadarTierFrames?,
    val detail: RadarTierFrames?,
    val motion: PhysicalRadarMotion?,
    val pairMotions: List<PhysicalRadarMotion?>,
    val latestDetailIntensity: IntensityGrid? = null,
    val latestDetailSnow: IntensityGrid? = null,
    val latestDetailCoverage: IntensityGrid? = null,
    val detailFailureMessage: String? = null,
    val providerSelection: RadarProviderSelection = RadarProviderSelection(
        RadarProviderKind.OPEN_RAINVIEWER,
        RadarProviderKind.OPEN_RAINVIEWER,
    ),
    val region: RegionalRadarArea? = null,
    val denseVelocity: Map<RadarResolutionTier, DenseVelocitySet> = emptyMap(),
    val legacyArchive: LegacyRadarArchive? = null,
    val mapCoverage: RadarCoverageRaster? = null,
) {
    private val released = AtomicBoolean(false)

    init {
        require(regional != null || detail != null || legacyArchive != null) { "Radar session needs radar data" }
    }

    val frames: List<RadarBitmapFrame> get() = (detail ?: regional)?.frames.orEmpty()
    val timelineFrames: List<RainViewerFrame>
        get() = legacyArchive?.frames?.map { it.frame } ?: frames.map { it.frame }
    val bounds: GeoQuad get() = (detail ?: regional)?.bounds ?: requireNotNull(region).bounds
    val isReleased: Boolean get() = released.get()

    fun tier(tier: RadarResolutionTier): RadarTierFrames? = when (tier) {
        RadarResolutionTier.REGIONAL -> regional
        RadarResolutionTier.DETAIL -> detail
    }

    fun velocity(tier: RadarResolutionTier): DenseVelocitySet? = denseVelocity[tier]

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val released = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        listOfNotNull(regional, detail).flatMap { it.frames }.forEach {
            if (released.add(it.bitmap) && !it.bitmap.isRecycled) it.bitmap.recycle()
            it.velocityBitmap?.let { velocity ->
                if (released.add(velocity) && !velocity.isRecycled) velocity.recycle()
            }
        }
        mapCoverage?.bitmap?.let { if (released.add(it) && !it.isRecycled) it.recycle() }
    }
}

class RadarSessionLoader(
    private val endpoint: RadarEndpoint = RainViewerEndpoint(),
) {
    suspend fun load(
        place: SavedPlace,
        maxFrames: Int? = null,
        mode: RadarLoadMode = RadarLoadMode.SCREEN_TWO_TIER,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        showLikelySnow: Boolean = false,
    ): RadarSession = coroutineScope {
        val manifest = RainViewerManifestParser.parse(endpoint.fetchManifest())
        val selectedFrames = maxFrames?.let { manifest.frames.takeLast(it.coerceAtLeast(2)) }
            ?: manifest.frames
        val regionalPlan = RainViewerRasterPlanner.forTier(place, RadarResolutionTier.REGIONAL)
        val detailPlan = RainViewerRasterPlanner.forTier(place, RadarResolutionTier.DETAIL)
        // One provider-published mask is loaded per session, not per radar frame. Screen loads
        // use the exact regional raster plan so the unknown-area scrim remains aligned; compact
        // Now/alert loads retain the detail grid used to distinguish clear from unknown.
        val coverageRasterDeferred = if (mode == RadarLoadMode.SCREEN_TWO_TIER) {
            async(Dispatchers.IO) {
                try {
                    downloadCoverageRaster(manifest.host, regionalPlan)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
        } else null
        val coverageGridDeferred = if (mode == RadarLoadMode.ALERT_ANALYSIS) {
            async(Dispatchers.IO) {
                try {
                    downloadCoverageGrid(manifest.host, detailPlan)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            }
        } else null
        val complete = AtomicInteger(0)
        val total = selectedFrames.size * (regionalPlan.segments.size + detailPlan.segments.size)
        onProgress(0, total)

        var regionalDownload: DownloadedTier? = null
        var detailDownload: DownloadedTier? = null
        var detailFailure: String? = null
        try {
            regionalDownload = try {
                downloadTier(
                    manifest.host,
                    selectedFrames,
                    RadarResolutionTier.REGIONAL,
                    regionalPlan,
                    keepLatestSamplingGrid = false,
                    complete,
                    total,
                    onProgress,
                    showLikelySnow,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (mode == RadarLoadMode.SCREEN_TWO_TIER) throw failure
                null
            }
            detailDownload = try {
                downloadTier(
                    manifest.host,
                    selectedFrames,
                    RadarResolutionTier.DETAIL,
                    detailPlan,
                    keepLatestSamplingGrid = mode == RadarLoadMode.ALERT_ANALYSIS,
                    complete,
                    total,
                    onProgress,
                    showLikelySnow,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (mode == RadarLoadMode.ALERT_ANALYSIS) throw failure
                detailFailure = failure.message ?: "Local-detail radar could not be loaded"
                null
            }
        } catch (failure: Throwable) {
            listOfNotNull(regionalDownload, detailDownload)
                .flatMap { it.tierFrames.frames }
                .forEach {
                if (!it.bitmap.isRecycled) it.bitmap.recycle()
            }
            throw failure
        }

        val regionalAnalyses = regionalDownload?.analyses.orEmpty()
        val detailAnalyses = detailDownload?.analyses.orEmpty()
        val regionalAggregate = estimatePhysicalAggregate(
            regionalAnalyses.takeLast(5),
            RadarResolutionTier.REGIONAL,
            regionalDownload?.tierFrames?.bounds,
        )
        val detailAggregate = estimatePhysicalAggregate(
            detailAnalyses.takeLast(5),
            RadarResolutionTier.DETAIL,
            detailDownload?.tierFrames?.bounds,
        )
        val pairMotions = (0 until selectedFrames.lastIndex).map { index ->
            val regionalPair = estimatePhysicalPair(
                regionalAnalyses.getOrNull(index),
                regionalAnalyses.getOrNull(index + 1),
                RadarResolutionTier.REGIONAL,
                regionalDownload?.tierFrames?.bounds,
            )
            val detailPair = estimatePhysicalPair(
                detailAnalyses.getOrNull(index),
                detailAnalyses.getOrNull(index + 1),
                RadarResolutionTier.DETAIL,
                detailDownload?.tierFrames?.bounds,
            )
            RadarMotionPolicy.preferred(regionalPair, detailPair)
        }
        fun dense(download: DownloadedTier?): DenseVelocitySet? {
            download ?: return null
            val fields: List<RadarVelocityField?> = (0 until download.analyses.lastIndex).map { index ->
                DenseRadarMotionEstimator.estimatePair(
                    download.analyses[index],
                    download.analyses[index + 1],
                    download.tierFrames.frames[index].bitmap.width,
                    download.tierFrames.frames[index].bitmap.height,
                )
            }
            return DenseVelocitySet(fields, DenseRadarMotionEstimator.aggregate(fields))
        }
        val denseVelocity = buildMap {
            dense(regionalDownload)?.let { put(RadarResolutionTier.REGIONAL, it) }
            dense(detailDownload)?.let { put(RadarResolutionTier.DETAIL, it) }
        }
        val detailCoverage = coverageGridDeferred?.await()
        val mapCoverage = coverageRasterDeferred?.await()
        RadarSession(
            place = place,
            regional = regionalDownload?.tierFrames,
            detail = detailDownload?.tierFrames,
            motion = RadarMotionPolicy.preferred(regionalAggregate, detailAggregate),
            pairMotions = pairMotions,
            latestDetailIntensity = detailDownload?.latestSamplingGrid,
            latestDetailSnow = detailDownload?.latestSnowGrid,
            latestDetailCoverage = detailCoverage,
            detailFailureMessage = detailFailure,
            denseVelocity = denseVelocity,
            mapCoverage = mapCoverage,
        )
    }

    private data class DownloadedFrame(
        val bitmapFrame: RadarBitmapFrame,
        val analysis: TimedIntensityGrid,
        val samplingGrid: IntensityGrid?,
        val snowGrid: IntensityGrid?,
    )

    private data class DownloadedTier(
        val tierFrames: RadarTierFrames,
        val analyses: List<TimedIntensityGrid>,
        val latestSamplingGrid: IntensityGrid?,
        val latestSnowGrid: IntensityGrid?,
    )

    private suspend fun downloadTier(
        host: String,
        frames: List<RainViewerFrame>,
        tier: RadarResolutionTier,
        rasterPlan: RainViewerRasterPlan,
        keepLatestSamplingGrid: Boolean,
        complete: AtomicInteger,
        total: Int,
        onProgress: (completed: Int, total: Int) -> Unit,
        showLikelySnow: Boolean,
    ): DownloadedTier = coroutineScope {
        val allocated = Collections.synchronizedList(mutableListOf<Bitmap>())
        val dispatcher = Dispatchers.IO.limitedParallelism(3)
        val images = try {
            frames.map { frame ->
                async(dispatcher) {
                    val sourceBitmaps = ArrayList<Bitmap>(rasterPlan.segments.size)
                    val bitmap = try {
                        rasterPlan.segments.forEach { segment ->
                            val bytes = endpoint.fetchImage(
                                buildCoordinateRadarUrl(
                                    host,
                                    frame,
                                    segment.center,
                                    zoom = rasterPlan.zoom,
                                    imageSize = rasterPlan.imageSize,
                                    showLikelySnow = showLikelySnow,
                                ),
                            )
                            currentCoroutineContext().ensureActive()
                            val decoded = decode(bytes, rasterPlan.imageSize)
                            sourceBitmaps += decoded
                            val count = complete.incrementAndGet()
                            withContext(Dispatchers.Main.immediate) { onProgress(count, total) }
                        }
                        renderRasterPlan(rasterPlan, sourceBitmaps)
                    } catch (failure: Throwable) {
                        sourceBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                        throw failure
                    }
                    allocated += bitmap
                    currentCoroutineContext().ensureActive()
                    val motionGrid = bitmap.toAnalysisGrid(MOTION_ANALYSIS_SIZE)
                    val decoded = bitmap.encodeOpenForRendering(
                        showLikelySnow,
                        keepLatestSamplingGrid && frame == frames.last(),
                    )
                    val result = DownloadedFrame(
                        bitmapFrame = RadarBitmapFrame(frame, bitmap),
                        analysis = TimedIntensityGrid(frame.time, motionGrid),
                        samplingGrid = decoded?.severity,
                        snowGrid = decoded?.snow,
                    )
                    result
                }
            }.awaitAll().sortedBy { it.bitmapFrame.frame.time }
        } catch (failure: Throwable) {
            allocated.forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        }
        DownloadedTier(
            tierFrames = RadarTierFrames(
                tier = tier,
                bounds = rasterPlan.targetBounds,
                frames = images.map { it.bitmapFrame },
            ),
            analyses = images.map { it.analysis },
            latestSamplingGrid = images.lastOrNull()?.samplingGrid,
            latestSnowGrid = images.lastOrNull()?.snowGrid,
        )
    }

    private fun renderRasterPlan(
        plan: RainViewerRasterPlan,
        sources: List<Bitmap>,
    ): Bitmap {
        require(sources.size == plan.segments.size && sources.isNotEmpty())
        val only = sources.singleOrNull()
        if (only != null && plan.outputWidth == plan.imageSize && plan.outputHeight == plan.imageSize &&
            plan.segments.single().destination == RainViewerRasterPlacement(
                0.0, 0.0, plan.imageSize.toDouble(), plan.imageSize.toDouble(),
            )
        ) return only

        val output = Bitmap.createBitmap(plan.outputWidth, plan.outputHeight, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            sources.zip(plan.segments).forEach { (source, segment) ->
                val destination = segment.destination
                canvas.drawBitmap(
                    source,
                    null,
                    RectF(
                        destination.left.toFloat(),
                        destination.top.toFloat(),
                        destination.right.toFloat(),
                        destination.bottom.toFloat(),
                    ),
                    paint,
                )
            }
            sources.forEach { if (!it.isRecycled) it.recycle() }
            output
        } catch (failure: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw failure
        }
    }

    private suspend fun downloadCoverageGrid(
        host: String,
        plan: RainViewerRasterPlan,
    ): IntensityGrid {
        val bitmap = downloadCoverageBitmap(host, plan)
        return try {
            bitmap.toCoverageGrid()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun downloadCoverageRaster(
        host: String,
        plan: RainViewerRasterPlan,
    ): RadarCoverageRaster = RadarCoverageRaster(
        bounds = plan.targetBounds,
        bitmap = downloadCoverageBitmap(host, plan),
        semantics = RadarCoverageSemantics.PROVIDER_PUBLISHED,
    )

    private suspend fun downloadCoverageBitmap(
        host: String,
        plan: RainViewerRasterPlan,
    ): Bitmap {
        val sources = ArrayList<Bitmap>(plan.segments.size)
        return try {
            plan.segments.forEach { segment ->
                val url = buildCoordinateCoverageUrl(
                    host, segment.center, plan.zoom, plan.imageSize,
                )
                val bytes = RainViewerCoverageCache.get(url) { endpoint.fetchImage(url) }
                currentCoroutineContext().ensureActive()
                sources += decode(bytes, plan.imageSize)
            }
            renderRasterPlan(plan, sources)
        } catch (failure: Throwable) {
            sources.forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        }
    }

    private fun decode(bytes: ByteArray, expectedSize: Int): Bitmap {
        require(bytes.isNotEmpty()) { "Radar frame was empty" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("Radar frame could not be decoded")
        require(bitmap.width == expectedSize && bitmap.height == expectedSize) {
            bitmap.recycle()
            "Radar frame dimensions were not $expectedSize by $expectedSize"
        }
        return bitmap
    }
}

private fun estimatePhysicalPair(
    before: TimedIntensityGrid?,
    after: TimedIntensityGrid?,
    tier: RadarResolutionTier,
    bounds: GeoQuad?,
): PhysicalRadarMotion? {
    if (before == null || after == null || bounds == null) return null
    val estimate = RadarMotionEstimator.estimatePair(before, after) ?: return null
    val span = WebMercator.worldFractionSpan(bounds)
    return PhysicalRadarMotion.fromAnalysisPixels(
        estimate,
        tier,
        before.grid.width,
        before.grid.height,
        span.width,
        span.height,
    ).takeIf(RadarMotionPolicy::usable)
}

private fun estimatePhysicalAggregate(
    frames: List<TimedIntensityGrid>,
    tier: RadarResolutionTier,
    bounds: GeoQuad?,
): PhysicalRadarMotion? {
    val first = frames.firstOrNull() ?: return null
    bounds ?: return null
    val estimate = RadarMotionEstimator.estimate(frames) ?: return null
    val span = WebMercator.worldFractionSpan(bounds)
    return PhysicalRadarMotion.fromAnalysisPixels(
        estimate,
        tier,
        first.grid.width,
        first.grid.height,
        span.width,
        span.height,
    ).takeIf(RadarMotionPolicy::usable)
}

private fun Bitmap.toAnalysisGrid(targetSize: Int): IntensityGrid {
    val targetWidth = minOf(width, targetSize)
    val targetHeight = minOf(height, targetSize)
    val values = FloatArray(targetWidth * targetHeight)
    val row = IntArray(width)
    for (sourceY in 0 until height) {
        getPixels(row, 0, width, 0, sourceY, width, 1)
        val targetY = sourceY * targetHeight / height
        for (sourceX in 0 until width) {
            val targetX = sourceX * targetWidth / width
            val alpha = ((row[sourceX] ushr 24) and 0xff) / 255f
            val index = targetY * targetWidth + targetX
            if (alpha > values[index]) values[index] = alpha
        }
    }
    return IntensityGrid(targetWidth, targetHeight, values)
}

private data class DecodedOpenRaster(
    val severity: IntensityGrid,
    val snow: IntensityGrid,
)

/** RainViewer coverage masks are transparent where covered and opaque black where absent. */
private fun Bitmap.toCoverageGrid(): IntensityGrid {
    val values = FloatArray(width * height)
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val alpha = ((row[x] ushr 24) and 0xff) / 255f
            values[y * width + x] = 1f - alpha
        }
    }
    return IntensityGrid(width, height, values)
}

/**
 * Replace provider RGBA with one renderer-friendly fetch: R=shared intensity,
 * G=likely-snow confidence, B=coverage, A=opaque. Motion was sampled beforehand.
 */
private fun Bitmap.encodeOpenForRendering(
    distinguishSnow: Boolean,
    retainPointGrid: Boolean,
): DecodedOpenRaster? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val severity = if (retainPointGrid) FloatArray(pixels.size) else null
    val snowGrid = if (retainPointGrid) FloatArray(pixels.size) else null
    // RainViewer smoothing produces a small, repeated colour set. Cache decoded
    // RGBA values so projection onto both published colour curves is not repeated
    // for every pixel in every frame.
    val decodedColours = HashMap<Int, com.rainalarm.app.domain.OpenRadarDecoded>()
    pixels.indices.forEach { index ->
        val source = pixels[index]
        val decoded = decodedColours.getOrPut(source) {
            OpenRadarColorScale.decode(source, distinguishSnow)
        }
        severity?.set(index, decoded.severity)
        snowGrid?.set(index, decoded.snowConfidence)
        val intensity = (decoded.sharedIntensity * 255f).toInt().coerceIn(0, 255)
        val snow = (decoded.snowConfidence * 255f).toInt().coerceIn(0, 255)
        val coverage = (decoded.coverage * 255f).toInt().coerceIn(0, 255)
        pixels[index] = (0xff shl 24) or (intensity shl 16) or (snow shl 8) or coverage
    }
    setPixels(pixels, 0, width, 0, 0, width, height)
    return severity?.let {
        DecodedOpenRaster(IntensityGrid(width, height, it),
            IntensityGrid(width, height, requireNotNull(snowGrid)))
    }
}
