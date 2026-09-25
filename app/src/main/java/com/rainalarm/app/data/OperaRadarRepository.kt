package com.rainalarm.app.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import com.rainalarm.app.domain.DenseRadarMotionEstimator
import com.rainalarm.app.domain.DenseVelocitySet
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.OpenRadarColorScale
import com.rainalarm.app.domain.PhysicalRadarMotion
import com.rainalarm.app.domain.RadarMotionEstimator
import com.rainalarm.app.domain.RadarMotionPolicy
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.RadarVelocityField
import com.rainalarm.app.domain.TimedIntensityGrid
import com.rainalarm.app.domain.WebMercator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class OperaRangeResult(val bytes: ByteArray, val fileLength: Long)

interface OperaRangeEndpoint {
    val cacheNamespace: String get() = "${javaClass.name}@${System.identityHashCode(this)}"
    /** Returns null only when the deterministic object key does not exist. */
    suspend fun range(key: String, firstByte: Long, lastByteInclusive: Long): OperaRangeResult?
}

class PublicOperaRangeEndpoint(
    private val root: String = "https://s3.waw3-1.cloudferro.com/openradar-24h",
) : OperaRangeEndpoint {
    override val cacheNamespace: String = root.removeSuffix("/")
    override suspend fun range(
        key: String,
        firstByte: Long,
        lastByteInclusive: Long,
    ): OperaRangeResult? = withContext(Dispatchers.IO) {
        require(key.matches(Regex("\\d{4}/\\d{2}/\\d{2}/OPERA/COMP/OPERA@\\d{8}T\\d{4}@0@DBZH\\.tiff"))) {
            "Invalid OPERA object key"
        }
        require(firstByte >= 0L && lastByteInclusive >= firstByte)
        val url = URL("${root.removeSuffix("/")}/$key")
        require(url.protocol == "https" && url.host == "s3.waw3-1.cloudferro.com") {
            "Unexpected OPERA radar host"
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Range", "bytes=$firstByte-$lastByteInclusive")
        connection.setRequestProperty("Accept", "image/tiff,application/octet-stream")
        connection.setRequestProperty("User-Agent", "RainAlarm/0.3")
        try {
            when (val response = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                HttpURLConnection.HTTP_PARTIAL -> Unit
                429 -> throw IOException("OPERA radar is temporarily rate limited")
                in 500..599 -> throw IOException("OPERA radar is temporarily unavailable")
                else -> throw IOException("OPERA radar returned $response instead of a byte range")
            }
            val contentRange = requireNotNull(connection.getHeaderField("Content-Range")) {
                "OPERA response did not identify its byte range"
            }
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange)
                ?: error("OPERA response had an invalid byte range")
            require(match.groupValues[1].toLong() == firstByte)
            val responseEnd = match.groupValues[2].toLong()
            val fileLength = match.groupValues[3].toLong()
            require(responseEnd in firstByte..lastByteInclusive && fileLength > responseEnd)
            val expected = (responseEnd - firstByte + 1).toInt()
            require(expected <= MAX_RANGE_BYTES) { "OPERA byte range is too large" }
            val advertised = connection.contentLengthLong
            require(advertised == expected.toLong()) { "OPERA response range length changed" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(expected)
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= expected) { "OPERA range exceeded its declared size" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size == expected) { "OPERA byte range was truncated" }
            OperaRangeResult(bytes, fileLength)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RANGE_BYTES = 8 * 1024 * 1024
    }
}

internal data class OperaRangeAccess(
    val result: OperaRangeResult?,
    val networkRequests: Int,
    val networkBytes: Long,
)

/**
 * Coalesces a regional COG slab only when the transfer stays small and below 1.5 times the
 * required compressed blocks. This trades a bounded amount of adjacent COG data for far fewer
 * high-latency S3 range round trips; sparse footprints retain precise per-block ranges.
 */
internal object OperaRangePlanner {
    fun groups(metadata: OperaCogMetadata, tileIndices: Collection<Int>): List<List<Int>> {
        if (tileIndices.isEmpty()) return emptyList()
        val sorted = tileIndices.distinct().sortedBy { metadata.tileOffsets[it] }
        val groups = mutableListOf<MutableList<Int>>()
        sorted.forEach { index ->
            val current = groups.lastOrNull()
            val previous = current?.lastOrNull()
            val previousEnd = previous?.let { metadata.tileOffsets[it] + metadata.tileByteCounts[it] }
            val candidateRequiredBytes = current?.sumOf { metadata.tileByteCounts[it] }
                ?.plus(metadata.tileByteCounts[index]) ?: 0L
            val candidateEnclosingBytes = current?.firstOrNull()?.let { first ->
                metadata.tileOffsets[index] + metadata.tileByteCounts[index] - metadata.tileOffsets[first]
            } ?: 0L
            val canJoin = previousEnd != null && (
                metadata.tileOffsets[index] <= previousEnd + MAX_ALIGNMENT_GAP_BYTES ||
                    (candidateEnclosingBytes <= MAX_COALESCED_RANGE_BYTES &&
                        candidateEnclosingBytes * MAX_OVERFETCH_DENOMINATOR <=
                        candidateRequiredBytes * MAX_OVERFETCH_NUMERATOR)
                )
            if (!canJoin) groups += mutableListOf(index) else current.add(index)
        }
        return groups
    }

    private const val MAX_COALESCED_RANGE_BYTES = 2L * 1024L * 1024L
    private const val MAX_OVERFETCH_NUMERATOR = 3L
    private const val MAX_OVERFETCH_DENOMINATOR = 2L
    private const val MAX_ALIGNMENT_GAP_BYTES = 4L * 1024L
}

internal data class OperaLatestProbeResult<T>(val selected: T, val completed: List<T>)

/**
 * Tries a few deterministic validity keys in parallel, but always selects the newest available
 * key rather than whichever request happens to finish first. A second bounded batch is attempted
 * only when the first batch is entirely absent or incompatible.
 */
internal object OperaLatestProbePolicy {
    suspend fun <T> newest(
        candidates: List<OperaFrameKey>,
        batchSize: Int = 3,
        attempt: suspend (OperaFrameKey) -> T,
        available: (T) -> Boolean,
    ): OperaLatestProbeResult<T> {
        require(batchSize > 0)
        val completed = mutableListOf<T>()
        for (batch in candidates.chunked(batchSize)) {
            val results = coroutineScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) {
                        try {
                            attempt(candidate)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }.awaitAll()
            }
            completed += results.filterNotNull()
            results.firstOrNull { it != null && available(it) }?.let { selected ->
                return OperaLatestProbeResult(requireNotNull(selected), completed)
            }
        }
        throw IOException("No recent OPERA observation is published")
    }
}

/**
 * Process-wide immutable COG source cache shared by screen, Now and alert loaders. Only compressed
 * HTTP ranges are retained (32 MiB LRU); decoded float tiles remain session-local and short-lived.
 * Independent-scope single-flight fetches survive cancellation of any one awaiting UI session.
 */
internal object OperaSharedSourceCache {
    data class Stats(val compressedEntries: Int, val compressedBytes: Int, val inFlight: Int)
    private data class Key(
        val namespace: String,
        val objectKey: String,
        val first: Long,
        val last: Long,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val sourceLimiter = Semaphore(MAX_SHARED_NETWORK_CONCURRENCY)
    private val decodeRenderLimiter = Semaphore(MAX_SHARED_DECODE_RENDER_CONCURRENCY)
    private val inFlight = HashMap<Key, Deferred<OperaRangeResult?>>()
    private val ranges = object : LinkedHashMap<Key, OperaRangeResult>(32, 0.75f, true) {}
    private val metadata = object : LinkedHashMap<String, OperaCogMetadata>(64, 0.75f, true) {}
    private var rangeBytes = 0

    suspend fun range(
        endpoint: OperaRangeEndpoint,
        objectKey: String,
        first: Long,
        last: Long,
    ): OperaRangeAccess {
        val key = Key(endpoint.cacheNamespace, objectKey, first, last)
        var creator = false
        val deferred = mutex.withLock {
            ranges[key]?.let { return OperaRangeAccess(it, 0, 0L) }
            inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    sourceLimiter.withPermit { endpoint.range(objectKey, first, last) }.also { result ->
                        if (result != null) mutex.withLock { store(key, result) }
                    }
                } finally {
                    mutex.withLock { inFlight.remove(key) }
                }
            }.also {
                creator = true
                inFlight[key] = it
                it.start()
            }
        }
        val result = deferred.await()
        return OperaRangeAccess(
            result,
            if (creator) 1 else 0,
            if (creator) result?.bytes?.size?.toLong() ?: 0L else 0L,
        )
    }

    @Synchronized fun metadata(namespace: String, key: String): OperaCogMetadata? =
        metadata["$namespace|$key"]

    @Synchronized fun putMetadata(namespace: String, key: String, value: OperaCogMetadata) {
        metadata["$namespace|$key"] = value
        while (metadata.size > MAX_METADATA_ENTRIES) metadata.entries.iterator().let {
            it.next(); it.remove()
        }
    }

    suspend fun stats(): Stats = mutex.withLock {
        Stats(ranges.size, rangeBytes, inFlight.size)
    }

    /**
     * Bounds the high-water memory used by inflated COG blocks and their two output rasters
     * across screen, Now and alert consumers. Network requests are deliberately made before
     * entering this permit, so all eight source slots can remain busy.
     */
    suspend fun <T> withDecodeRenderPermit(block: suspend () -> T): T =
        decodeRenderLimiter.withPermit { block() }

    private fun store(key: Key, value: OperaRangeResult) {
        ranges.put(key, value)?.let { rangeBytes -= it.bytes.size }
        rangeBytes += value.bytes.size
        val iterator = ranges.entries.iterator()
        while (rangeBytes > MAX_COMPRESSED_BYTES && iterator.hasNext()) {
            rangeBytes -= iterator.next().value.bytes.size
            iterator.remove()
        }
    }

    private const val MAX_COMPRESSED_BYTES = 32 * 1024 * 1024
    private const val MAX_METADATA_ENTRIES = 64
    internal const val MAX_SHARED_NETWORK_CONCURRENCY = 8
    private const val MAX_SHARED_DECODE_RENDER_CONCURRENCY = 2
}

private data class OperaRasterResult(
    val bitmap: Bitmap,
    val severity: IntensityGrid,
    val coverage: IntensityGrid,
)

private data class OperaDownloadedFrame(
    val frame: RainViewerFrame,
    val regionalBitmap: Bitmap?,
    val detailBitmap: Bitmap,
    val regionalAnalysis: IntensityGrid?,
    val detailAnalysis: IntensityGrid,
    val latestRegionalSeverity: IntensityGrid?,
    val latestRegionalCoverage: IntensityGrid?,
    val latestDetailSeverity: IntensityGrid?,
    val latestDetailCoverage: IntensityGrid?,
)

@SuppressLint("LogNotTimber")
class OperaRadarSessionLoader(
    private val endpoint: OperaRangeEndpoint = PublicOperaRangeEndpoint(),
    private val now: () -> Instant = Instant::now,
) {
    suspend fun load(
        place: SavedPlace,
        maxFrames: Int? = null,
        mode: RadarLoadMode = RadarLoadMode.SCREEN_TWO_TIER,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): RadarSession = coroutineScope {
        require(OperaProductDomain.contains(GeoPoint(place.latitude, place.longitude))) {
            "OPERA coverage is unavailable at ${place.name}"
        }
        val area = RegionalRadarAreas.forPoint(place.latitude, place.longitude)
        val startedNanos = System.nanoTime()
        val latest = discoverLatest()
        val discoveryDoneNanos = System.nanoTime()
        var rangeRequests = latest.networkRequests
        var networkBytes = latest.networkBytes
        val requested = OperaFrameKeyPolicy.history(
            latest.key.validityEpochSeconds,
            maxFrames ?: OperaFrameKeyPolicy.DEFAULT_FRAME_COUNT,
        )
        val metadataDispatcher = Dispatchers.IO.limitedParallelism(MAX_METADATA_CONCURRENCY)
        val available = requested.map { key ->
            async(metadataDispatcher) { metadata(key) }
        }.awaitAll().also { accesses ->
            rangeRequests += accesses.sumOf { it.networkRequests }
            networkBytes += accesses.sumOf { it.networkBytes }
        }.mapNotNull { it.metadata?.let { metadata -> it.key to metadata } }
            .sortedBy { it.first.validityEpochSeconds }
        val metadataDoneNanos = System.nanoTime()
        require(available.size >= 2) { "OPERA radar has fewer than two usable observations" }
        validateStableGeometry(available.map { it.second })
        val reference = available.last().second
        // Foreground map sessions need the exact Metro-shaped regional raster. Now/alert
        // analysis only samples the selected-place detail tier, so avoid decoding most of the
        // European COG again for those simultaneous consumers.
        val regionalPlan = if (mode == RadarLoadMode.SCREEN_TWO_TIER) {
            OperaSamplingPlanner.regional(reference, place)
        } else null
        val detailPlan = OperaSamplingPlanner.detail(reference, place)
        val requiredTiles = (regionalPlan?.requiredTiles?.asSequence() ?: emptySequence())
            .plus(detailPlan.requiredTiles.asSequence())
            .distinct().sorted().toList().toIntArray()
        val rangeGroups = OperaRangePlanner.groups(reference, requiredTiles.toList())
        val requestedCompressedBytes = requiredTiles.sumOf { reference.tileByteCounts[it] }
        val enclosingBytes = if (requiredTiles.isEmpty()) 0L else {
            val first = requiredTiles.minOf { reference.tileOffsets[it] }
            val lastIndex = requiredTiles.maxBy { reference.tileOffsets[it] }
            reference.tileOffsets[lastIndex] + reference.tileByteCounts[lastIndex] - first
        }
        Log.i(
            TAG,
            "OPERA plan area=${area?.id ?: "opera-domain"} tiles=${requiredTiles.size} groups=${rangeGroups.size} " +
                "compressedBytes=$requestedCompressedBytes enclosingBytes=$enclosingBytes " +
                "tileIds=${requiredTiles.joinToString(",")}",
        )
        val complete = AtomicInteger(0)
        val fetchNanos = AtomicLong(0L)
        val maximumFetchNanos = AtomicLong(0L)
        val processNanos = AtomicLong(0L)
        val maximumProcessNanos = AtomicLong(0L)
        val total = available.size * (if (regionalPlan != null) 2 else 1)
        onProgress(0, total)
        val outputs = ArrayList<OperaDownloadedFrame>(available.size)
        val outputSlots = arrayOfNulls<OperaDownloadedFrame>(available.size)
        try {
            // Start every immutable frame fetch so the process-wide network semaphore, rather
            // than a two-frame batch, controls range concurrency. The limited Default dispatcher
            // still bounds decode/resample work: suspended range requests release their worker,
            // while only two frames can inflate/render at once. Regional and detail for each
            // frame share decoded tiles, which are discarded as soon as both rasters are built.
            val frameDispatcher = Dispatchers.Default.limitedParallelism(MAX_FRAME_CONCURRENCY)
            val loadedOutputs = coroutineScope {
                available.mapIndexed { index, (key, metadata) ->
                    async(frameDispatcher) {
                        currentCoroutineContext().ensureActive()
                        val fetchStarted = System.nanoTime()
                        val fetched = fetchTiles(key, metadata, requiredTiles)
                        val fetchedNanos = System.nanoTime() - fetchStarted
                        fetchNanos.addAndGet(fetchedNanos)
                        maximumFetchNanos.accumulateAndGet(fetchedNanos, ::maxOf)
                        val output = OperaSharedSourceCache.withDecodeRenderPermit {
                            val processStarted = System.nanoTime()
                            val tiles = decodeTiles(fetched.compressed, metadata)
                            val regional = regionalPlan?.let { plan ->
                                render(metadata, tiles, plan).also {
                                    withContext(Dispatchers.Main.immediate) {
                                        onProgress(complete.incrementAndGet(), total)
                                    }
                                }
                            }
                            val detail = render(metadata, tiles, detailPlan)
                            withContext(Dispatchers.Main.immediate) {
                                onProgress(complete.incrementAndGet(), total)
                            }
                            val latest = index == available.lastIndex
                            OperaDownloadedFrame(
                                frame = RainViewerFrame(key.validityEpochSeconds, key.key, false),
                                regionalBitmap = regional?.bitmap,
                                detailBitmap = detail.bitmap,
                                regionalAnalysis = regional?.severity?.let(::analysisGrid),
                                detailAnalysis = analysisGrid(detail.severity),
                                latestRegionalSeverity = regional?.severity?.takeIf { latest },
                                latestRegionalCoverage = regional?.coverage?.takeIf { latest },
                                latestDetailSeverity = detail.severity.takeIf { latest },
                                latestDetailCoverage = detail.coverage.takeIf { latest },
                            ).also {
                                val processedNanos = System.nanoTime() - processStarted
                                processNanos.addAndGet(processedNanos)
                                maximumProcessNanos.accumulateAndGet(processedNanos, ::maxOf)
                            }
                        }
                        // Keep ownership visible to the failure path even when another sibling
                        // fails before awaitAll can return the successfully rendered outputs.
                        outputSlots[index] = output
                        Triple(output, fetched.networkRequests, fetched.networkBytes)
                    }
                }.awaitAll()
            }
            loadedOutputs.forEach { (output, requests, bytes) ->
                outputs += output
                rangeRequests += requests
                networkBytes += bytes
            }
        } catch (failure: Throwable) {
            outputSlots.filterNotNull().forEach { output ->
                output.regionalBitmap?.let { if (!it.isRecycled) it.recycle() }
                if (!output.detailBitmap.isRecycled) output.detailBitmap.recycle()
            }
            throw failure
        }
        val regionalAnalyses = outputs.mapNotNull { output ->
            output.regionalAnalysis?.let { TimedIntensityGrid(output.frame.time, it) }
        }
        val detailAnalyses = outputs.map { TimedIntensityGrid(it.frame.time, it.detailAnalysis) }
        val regionalTier = regionalPlan?.let {
            RadarTierFrames(
                RadarResolutionTier.REGIONAL, it.bounds,
                outputs.map { RadarBitmapFrame(it.frame, requireNotNull(it.regionalBitmap)) },
            )
        }
        val detailTier = RadarTierFrames(
            RadarResolutionTier.DETAIL, detailPlan.bounds,
            outputs.map { RadarBitmapFrame(it.frame, it.detailBitmap) },
        )
        val pairMotions = (0 until outputs.lastIndex).map { index ->
            RadarMotionPolicy.preferred(
                regionalAnalyses.takeIf { it.size == outputs.size }?.let {
                    physicalPair(it[index], it[index + 1], RadarResolutionTier.REGIONAL,
                        requireNotNull(regionalPlan).bounds)
                },
                physicalPair(detailAnalyses[index], detailAnalyses[index + 1], RadarResolutionTier.DETAIL, detailPlan.bounds),
            )
        }
        fun dense(analyses: List<TimedIntensityGrid>, width: Int, height: Int): DenseVelocitySet {
            val fields: List<RadarVelocityField?> = analyses.zipWithNext().map { (before, after) ->
                DenseRadarMotionEstimator.estimatePair(before, after, width, height)
            }
            return DenseVelocitySet(fields, DenseRadarMotionEstimator.aggregate(fields))
        }
        val denseVelocity = buildMap {
            regionalPlan?.let { plan ->
                put(RadarResolutionTier.REGIONAL, dense(regionalAnalyses, plan.width, plan.height))
            }
            put(RadarResolutionTier.DETAIL, dense(detailAnalyses, detailPlan.width, detailPlan.height))
        }
        val regionalMotion = regionalAnalyses.takeIf { it.isNotEmpty() }?.let {
            physicalAggregate(it.takeLast(5), RadarResolutionTier.REGIONAL,
                requireNotNull(regionalPlan).bounds)
        }
        val detailMotion = physicalAggregate(
            detailAnalyses.takeLast(5), RadarResolutionTier.DETAIL, detailPlan.bounds,
        )
        val preferredMotion = RadarMotionPolicy.preferred(regionalMotion, detailMotion)
        fun futureFieldSummary(tier: RadarResolutionTier): String =
            denseVelocity[tier]?.futureField?.let { field ->
                val (dx, dy) = field.displacementAt(0.5, 0.5)
                "${"%.2f".format(dx)},${"%.2f".format(dy)}px/" +
                    "${field.sourceIntervalSeconds}s@${"%.2f".format(field.confidence)}"
            } ?: "none"
        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
        val cacheStats = OperaSharedSourceCache.stats()
        val latestRegional = outputs.last().latestRegionalSeverity
            ?: requireNotNull(outputs.last().latestDetailSeverity)
        val wetPixels = latestRegional.values.count { it >= OpenRadarColorScale.WET_SEVERITY_THRESHOLD }
        val maximumIndex = latestRegional.values.indices.maxByOrNull { latestRegional.values[it] } ?: 0
        val diagnosticBounds = regionalPlan?.bounds ?: detailPlan.bounds
        val (worldLeft, worldTop) = WebMercator.worldFraction(diagnosticBounds.topLeft)
        val (worldRight, worldBottom) = WebMercator.worldFraction(diagnosticBounds.bottomRight)
        val maximumPoint = WebMercator.pointAtWorldFraction(
            worldLeft + ((maximumIndex % latestRegional.width) + 0.5) / latestRegional.width *
                (worldRight - worldLeft),
            worldTop + ((maximumIndex / latestRegional.width) + 0.5) / latestRegional.height *
                (worldBottom - worldTop),
        )
        Log.i(
            TAG,
            "OPERA area=${area?.id ?: "opera-domain"} frames=${outputs.size} tiles=${requiredTiles.size} " +
                "ranges=$rangeRequests bytes=$networkBytes elapsedMs=$elapsedMillis " +
                "phasesMs=${(discoveryDoneNanos - startedNanos) / 1_000_000L}/" +
                "${(metadataDoneNanos - discoveryDoneNanos) / 1_000_000L}/" +
                "${(System.nanoTime() - metadataDoneNanos) / 1_000_000L} " +
                "fetchSumMaxMs=${fetchNanos.get() / 1_000_000L}/" +
                "${maximumFetchNanos.get() / 1_000_000L} processSumMaxMs=" +
                "${processNanos.get() / 1_000_000L}/${maximumProcessNanos.get() / 1_000_000L} " +
                "regional=${regionalPlan?.let { "${it.width}x${it.height}" } ?: "skipped"} " +
                "detail=${detailPlan.width}x${detailPlan.height} " +
                "cacheEntries=${cacheStats.compressedEntries} cacheBytes=${cacheStats.compressedBytes} " +
                "place=${"%.3f".format(place.latitude)},${"%.3f".format(place.longitude)} " +
                "detailBounds=${"%.3f".format(detailPlan.bounds.topLeft.latitude)}," +
                "${"%.3f".format(detailPlan.bounds.topLeft.longitude)}.." +
                "${"%.3f".format(detailPlan.bounds.bottomRight.latitude)}," +
                "${"%.3f".format(detailPlan.bounds.bottomRight.longitude)} " +
                "wetPixels=$wetPixels maxLat=${"%.2f".format(maximumPoint.latitude)} " +
                "maxLon=${"%.2f".format(maximumPoint.longitude)} " +
                "motion=${preferredMotion?.let {
                    "${"%.3f".format(it.pixelsPerMinute(RadarResolutionTier.REGIONAL).first)}," +
                        "${"%.3f".format(it.pixelsPerMinute(RadarResolutionTier.REGIONAL).second)}" +
                        "pxMin@${"%.2f".format(it.confidence)}"
                } ?: "none"} " +
                "denseRegional=${futureFieldSummary(RadarResolutionTier.REGIONAL)} " +
                "denseDetail=${futureFieldSummary(RadarResolutionTier.DETAIL)}",
        )
        RadarSession(
            place = place,
            regional = regionalTier,
            detail = detailTier,
            motion = preferredMotion,
            pairMotions = pairMotions,
            latestDetailIntensity = requireNotNull(outputs.last().latestDetailSeverity),
            latestDetailSnow = null,
            latestDetailCoverage = requireNotNull(outputs.last().latestDetailCoverage),
            providerSelection = RadarProviderSelection(
                RadarProviderKind.EUMETNET_OPERA,
                RadarProviderKind.EUMETNET_OPERA,
            ),
            region = area,
            denseVelocity = denseVelocity,
            mapCoverage = regionalPlan?.let { plan ->
                outputs.last().latestRegionalCoverage?.let { coverage ->
                    RadarCoverageRaster(
                        bounds = plan.bounds,
                        bitmap = coverage.toUnknownMaskBitmap(),
                        semantics = RadarCoverageSemantics.CURRENT_COMPOSITE,
                    )
                }
            },
        )
    }

    private data class MetadataAccess(
        val key: OperaFrameKey,
        val metadata: OperaCogMetadata?,
        val networkRequests: Int,
        val networkBytes: Long,
    )

    private suspend fun discoverLatest(): MetadataAccess {
        val probed = OperaLatestProbePolicy.newest(
            candidates = OperaFrameKeyPolicy.latestCandidates(now()),
            attempt = ::metadata,
            available = { it.metadata != null },
        )
        return probed.selected.copy(
            networkRequests = probed.completed.sumOf { it.networkRequests },
            networkBytes = probed.completed.sumOf { it.networkBytes },
        )
    }

    private suspend fun metadata(key: OperaFrameKey): MetadataAccess {
        OperaSharedSourceCache.metadata(endpoint.cacheNamespace, key.key)?.let {
            return MetadataAccess(key, it, 0, 0L)
        }
        val access = OperaSharedSourceCache.range(
            endpoint, key.key, 0L, METADATA_RANGE_BYTES - 1L,
        )
        val result = access.result ?: return MetadataAccess(
            key, null, access.networkRequests, access.networkBytes,
        )
        val parsed = OperaCogParser.parse(result.bytes, result.fileLength)
        OperaSharedSourceCache.putMetadata(endpoint.cacheNamespace, key.key, parsed)
        return MetadataAccess(key, parsed, access.networkRequests, access.networkBytes)
    }

    private data class FetchedTiles(
        val compressed: Map<Int, ByteArray>,
        val networkRequests: Int,
        val networkBytes: Long,
    )

    private suspend fun fetchTiles(
        key: OperaFrameKey,
        metadata: OperaCogMetadata,
        required: IntArray,
    ): FetchedTiles = coroutineScope {
        val compressed = HashMap<Int, ByteArray>(required.size)
        val groups = OperaRangePlanner.groups(metadata, required.toList())
        val dispatcher = Dispatchers.IO.limitedParallelism(MAX_RANGE_CONCURRENCY)
        var requests = 0
        var bytes = 0L
        groups.map { group ->
            async(dispatcher) {
                val first = metadata.tileOffsets[group.first()]
                val lastIndex = group.last()
                val last = metadata.tileOffsets[lastIndex] + metadata.tileByteCounts[lastIndex] - 1L
                val access = OperaSharedSourceCache.range(endpoint, key.key, first, last)
                val result = requireNotNull(access.result) {
                    "OPERA observation disappeared while loading"
                }
                require(result.fileLength == metadata.fileLength)
                val blocks = group.associateWith { index ->
                    val offset = (metadata.tileOffsets[index] - first).toInt()
                    val count = metadata.tileByteCounts[index].toInt()
                    require(offset >= 0 && offset + count <= result.bytes.size)
                    result.bytes.copyOfRange(offset, offset + count)
                }
                Triple(blocks, access.networkRequests, access.networkBytes)
            }
        }.awaitAll().forEach { (blocks, ownedRequests, loadedBytes) ->
            requests += ownedRequests
            bytes += loadedBytes
            blocks.forEach { (index, block) ->
                compressed[index] = block
            }
        }
        // Followers and cache hits report zero; only the consumer that initiated each shared fetch
        // accounts for that process-level request in diagnostics.
        FetchedTiles(compressed, requests, bytes)
    }

    private fun decodeTiles(
        compressed: Map<Int, ByteArray>,
        metadata: OperaCogMetadata,
    ): Map<Int, OperaDecodedTile> = compressed.mapValues { (index, bytesValue) ->
        OperaTileDecoder.decode(bytesValue, metadata, index)
    }

    private fun render(
        metadata: OperaCogMetadata,
        tiles: Map<Int, OperaDecodedTile>,
        plan: OperaSamplingPlan,
    ): OperaRasterResult {
        val severity = FloatArray(plan.width * plan.height)
        val coverage = FloatArray(severity.size)
        val pixels = IntArray(severity.size)
        val tilesByIndex = arrayOfNulls<OperaDecodedTile>(metadata.tileOffsets.size)
        tiles.forEach { (index, tile) -> tilesByIndex[index] = tile }
        fun raw(x: Int, y: Int): Float {
            if (x !in 0 until metadata.width || y !in 0 until metadata.height) return metadata.noData
            val tileIndex = y / metadata.tileHeight * metadata.tileColumns + x / metadata.tileWidth
            val tile = tilesByIndex[tileIndex] ?: return metadata.noData
            return tile.reflectivity((y % metadata.tileHeight) * metadata.tileWidth + x % metadata.tileWidth)
        }
        for (index in severity.indices) {
            val sx = plan.sourceX[index].toDouble()
            val sy = plan.sourceY[index].toDouble()
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            val fx = sx - left
            val fy = sy - top
            var coveredWeight = 0.0
            var intensity = 0.0
            val value00 = raw(left, top)
            val weight00 = (1.0 - fx) * (1.0 - fy)
            if (OperaTileDecoder.isCovered(value00, metadata.noData)) {
                coveredWeight += weight00
                intensity += OpenRadarColorScale.sharedIntensityForDbz(
                    OperaTileDecoder.reflectivityOrDry(value00),
                ) * weight00
            }
            val value10 = raw(left + 1, top)
            val weight10 = fx * (1.0 - fy)
            if (OperaTileDecoder.isCovered(value10, metadata.noData)) {
                coveredWeight += weight10
                intensity += OpenRadarColorScale.sharedIntensityForDbz(
                    OperaTileDecoder.reflectivityOrDry(value10),
                ) * weight10
            }
            val value01 = raw(left, top + 1)
            val weight01 = (1.0 - fx) * fy
            if (OperaTileDecoder.isCovered(value01, metadata.noData)) {
                coveredWeight += weight01
                intensity += OpenRadarColorScale.sharedIntensityForDbz(
                    OperaTileDecoder.reflectivityOrDry(value01),
                ) * weight01
            }
            val value11 = raw(left + 1, top + 1)
            val weight11 = fx * fy
            if (OperaTileDecoder.isCovered(value11, metadata.noData)) {
                coveredWeight += weight11
                intensity += OpenRadarColorScale.sharedIntensityForDbz(
                    OperaTileDecoder.reflectivityOrDry(value11),
                ) * weight11
            }
            val cover = coveredWeight.coerceIn(0.0, 1.0).toFloat()
            val shared = if (coveredWeight > 0.0) (intensity / coveredWeight).toFloat().coerceIn(0f, 1f) else 0f
            coverage[index] = cover
            severity[index] = OpenRadarColorScale.pointSeverity(shared, cover)
            val red = (shared * 255f).toInt().coerceIn(0, 255)
            val blue = (cover * 255f).toInt().coerceIn(0, 255)
            pixels[index] = (0xff shl 24) or (red shl 16) or blue
        }
        val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, plan.width, 0, 0, plan.width, plan.height)
        return OperaRasterResult(
            bitmap,
            IntensityGrid(plan.width, plan.height, severity),
            IntensityGrid(plan.width, plan.height, coverage),
        )
    }

    private fun validateStableGeometry(items: List<OperaCogMetadata>) {
        val expected = items.last()
        items.forEach { actual ->
            require(actual.width == expected.width && actual.height == expected.height &&
                actual.tileWidth == expected.tileWidth && actual.tileHeight == expected.tileHeight &&
                actual.pixelWidthMetres == expected.pixelWidthMetres &&
                actual.pixelHeightMetres == expected.pixelHeightMetres &&
                actual.tieEasting == expected.tieEasting && actual.tieNorthing == expected.tieNorthing &&
                actual.projection == expected.projection) {
                "OPERA radar geometry changed inside one observation window"
            }
        }
    }

    private fun analysisGrid(source: IntensityGrid): IntensityGrid {
        val scale = ceil(maxOf(source.width, source.height) / MOTION_SIZE.toDouble()).toInt().coerceAtLeast(1)
        if (scale == 1) return source
        val width = ceil(source.width / scale.toDouble()).toInt()
        val height = ceil(source.height / scale.toDouble()).toInt()
        val values = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var maximum = 0f
            for (sy in y * scale until minOf((y + 1) * scale, source.height)) {
                for (sx in x * scale until minOf((x + 1) * scale, source.width)) {
                    maximum = maxOf(maximum, source[sx, sy])
                }
            }
            values[y * width + x] = maximum
        }
        return IntensityGrid(width, height, values)
    }

    private fun physicalPair(
        before: TimedIntensityGrid,
        after: TimedIntensityGrid,
        tier: RadarResolutionTier,
        bounds: com.rainalarm.app.domain.GeoQuad,
    ): PhysicalRadarMotion? {
        val estimate = RadarMotionEstimator.estimatePair(before, after) ?: return null
        val span = WebMercator.worldFractionSpan(bounds)
        return PhysicalRadarMotion.fromAnalysisPixels(
            estimate, tier, before.grid.width, before.grid.height, span.width, span.height,
        ).takeIf(RadarMotionPolicy::usable)
    }

    private fun physicalAggregate(
        frames: List<TimedIntensityGrid>,
        tier: RadarResolutionTier,
        bounds: com.rainalarm.app.domain.GeoQuad,
    ): PhysicalRadarMotion? {
        val first = frames.firstOrNull() ?: return null
        val estimate = RadarMotionEstimator.estimate(frames) ?: return null
        val span = WebMercator.worldFractionSpan(bounds)
        return PhysicalRadarMotion.fromAnalysisPixels(
            estimate, tier, first.grid.width, first.grid.height, span.width, span.height,
        ).takeIf(RadarMotionPolicy::usable)
    }

    private companion object {
        const val TAG = "RainRadarOpera"
        const val METADATA_RANGE_BYTES = 64 * 1024L
        const val MAX_RANGE_CONCURRENCY = 9
        const val MAX_METADATA_CONCURRENCY = OperaSharedSourceCache.MAX_SHARED_NETWORK_CONCURRENCY
        const val MAX_FRAME_CONCURRENCY = 2
        const val MOTION_SIZE = 64
    }
}

/** Transparent means covered; opacity is proportional to provider no-data/unknown weight. */
private fun IntensityGrid.toUnknownMaskBitmap(): Bitmap {
    val pixels = IntArray(values.size) { index ->
        val alpha = ((1f - values[index].coerceIn(0f, 1f)) * 255f).roundToInt()
        alpha shl 24
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
