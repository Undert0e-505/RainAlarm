package com.rainalarm.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

const val RADAR_IMAGE_ZOOM = 7
const val RADAR_IMAGE_SIZE = 512
private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
private const val MOTION_ANALYSIS_SIZE = 64

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
): String {
    val hostUrl = URL(host)
    require(hostUrl.protocol == "https" && hostUrl.host.isNotBlank()) {
        "Radar tile host must use HTTPS"
    }
    require(frame.path.startsWith("/v2/radar/")) { "Invalid radar frame path" }
    require(zoom in 0..7) { "Radar image zoom must be between 0 and 7" }
    require(imageSize == 256 || imageSize == 512) { "Radar image size must be 256 or 512" }
    return "${host.removeSuffix("/")}${frame.path}/$imageSize/$zoom/" +
        "${place.latitude}/${place.longitude}/2/1_1.png"
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
    val detailFailureMessage: String? = null,
    val providerSelection: RadarProviderSelection = RadarProviderSelection(
        RadarProviderKind.OPEN_RAINVIEWER,
        RadarProviderKind.OPEN_RAINVIEWER,
    ),
    val region: RegionalRadarArea? = null,
    val denseVelocity: Map<RadarResolutionTier, DenseVelocitySet> = emptyMap(),
    val legacyArchive: LegacyRadarArchive? = null,
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
    ): RadarSession = coroutineScope {
        val manifest = RainViewerManifestParser.parse(endpoint.fetchManifest())
        val selectedFrames = maxFrames?.let { manifest.frames.takeLast(it.coerceAtLeast(2)) }
            ?: manifest.frames
        val complete = AtomicInteger(0)
        val total = radarRequestPlan(selectedFrames, mode).size
        onProgress(0, total)

        var regionalDownload: DownloadedTier? = null
        var detailDownload: DownloadedTier? = null
        var detailFailure: String? = null
        try {
            regionalDownload = try {
                downloadTier(
                    manifest.host,
                    place,
                    selectedFrames,
                    RadarResolutionTier.REGIONAL,
                    keepLatestSamplingGrid = false,
                    complete,
                    total,
                    onProgress,
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
                    place,
                    selectedFrames,
                    RadarResolutionTier.DETAIL,
                    keepLatestSamplingGrid = mode == RadarLoadMode.ALERT_ANALYSIS,
                    complete,
                    total,
                    onProgress,
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
        )
        val detailAggregate = estimatePhysicalAggregate(
            detailAnalyses.takeLast(5),
            RadarResolutionTier.DETAIL,
        )
        val pairMotions = (0 until selectedFrames.lastIndex).map { index ->
            val regionalPair = estimatePhysicalPair(
                regionalAnalyses.getOrNull(index),
                regionalAnalyses.getOrNull(index + 1),
                RadarResolutionTier.REGIONAL,
            )
            val detailPair = estimatePhysicalPair(
                detailAnalyses.getOrNull(index),
                detailAnalyses.getOrNull(index + 1),
                RadarResolutionTier.DETAIL,
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
        RadarSession(
            place = place,
            regional = regionalDownload?.tierFrames,
            detail = detailDownload?.tierFrames,
            motion = RadarMotionPolicy.preferred(regionalAggregate, detailAggregate),
            pairMotions = pairMotions,
            latestDetailIntensity = detailDownload?.latestSamplingGrid,
            detailFailureMessage = detailFailure,
            denseVelocity = denseVelocity,
        )
    }

    private data class DownloadedFrame(
        val bitmapFrame: RadarBitmapFrame,
        val analysis: TimedIntensityGrid,
        val samplingGrid: IntensityGrid?,
    )

    private data class DownloadedTier(
        val tierFrames: RadarTierFrames,
        val analyses: List<TimedIntensityGrid>,
        val latestSamplingGrid: IntensityGrid?,
    )

    private suspend fun downloadTier(
        host: String,
        place: SavedPlace,
        frames: List<RainViewerFrame>,
        tier: RadarResolutionTier,
        keepLatestSamplingGrid: Boolean,
        complete: AtomicInteger,
        total: Int,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): DownloadedTier = coroutineScope {
        val allocated = Collections.synchronizedList(mutableListOf<Bitmap>())
        val dispatcher = Dispatchers.IO.limitedParallelism(3)
        val images = try {
            frames.map { frame ->
                async(dispatcher) {
                    val bytes = endpoint.fetchImage(
                        buildCoordinateRadarUrl(
                            host,
                            frame,
                            place,
                            zoom = tier.zoom,
                            imageSize = tier.imageSize,
                        ),
                    )
                    currentCoroutineContext().ensureActive()
                    val bitmap = decode(bytes, tier.imageSize)
                    allocated += bitmap
                    currentCoroutineContext().ensureActive()
                    val result = DownloadedFrame(
                        bitmapFrame = RadarBitmapFrame(frame, bitmap),
                        analysis = TimedIntensityGrid(
                            frame.time,
                            bitmap.toAnalysisGrid(MOTION_ANALYSIS_SIZE),
                        ),
                        samplingGrid = if (keepLatestSamplingGrid && frame == frames.last()) {
                            bitmap.toOpenSeverityGrid()
                        } else {
                            null
                        },
                    )
                    val count = complete.incrementAndGet()
                    withContext(Dispatchers.Main.immediate) { onProgress(count, total) }
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
                bounds = WebMercator.imageBounds(
                    GeoPoint(place.latitude, place.longitude),
                    tier.zoom,
                    tier.imageSize,
                ),
                frames = images.map { it.bitmapFrame },
            ),
            analyses = images.map { it.analysis },
            latestSamplingGrid = images.lastOrNull()?.samplingGrid,
        )
    }

    private fun decode(bytes: ByteArray, expectedSize: Int): Bitmap {
        require(bytes.isNotEmpty()) { "Radar frame was empty" }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
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
): PhysicalRadarMotion? {
    if (before == null || after == null) return null
    val estimate = RadarMotionEstimator.estimatePair(before, after) ?: return null
    return PhysicalRadarMotion.fromAnalysisPixels(
        estimate,
        tier,
        before.grid.width,
        before.grid.height,
    ).takeIf(RadarMotionPolicy::usable)
}

private fun estimatePhysicalAggregate(
    frames: List<TimedIntensityGrid>,
    tier: RadarResolutionTier,
): PhysicalRadarMotion? {
    val first = frames.firstOrNull() ?: return null
    val estimate = RadarMotionEstimator.estimate(frames) ?: return null
    return PhysicalRadarMotion.fromAnalysisPixels(
        estimate,
        tier,
        first.grid.width,
        first.grid.height,
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

/** Keep alpha-only motion analysis separate from colour-calibrated point severity. */
private fun Bitmap.toOpenSeverityGrid(): IntensityGrid {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val values = FloatArray(pixels.size) { index -> OpenRadarColorScale.fromArgb(pixels[index]) }
    return IntensityGrid(width, height, values)
}
