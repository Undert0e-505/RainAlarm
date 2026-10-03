package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.MeteoNominalCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt

enum class LflFeedStatus {
    VALID,
    VALID_EMPTY,
    PARTIAL,
    STALE,
}

data class LflFlashPoint(
    val id: String,
    val observedAtEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
)

data class LflLightningFeed(
    val generationId: String,
    val regionId: String,
    val status: LflFeedStatus,
    val observedFromEpochSeconds: Long,
    val observedThroughEpochSeconds: Long,
    val completeThroughEpochSeconds: Long,
    val staleAfterEpochSeconds: Long,
    val points: List<LflFlashPoint>,
    val attribution: String,
) {
    fun isFreshAt(nowEpochSeconds: Long): Boolean =
        status in setOf(LflFeedStatus.VALID, LflFeedStatus.VALID_EMPTY) &&
            nowEpochSeconds < staleAfterEpochSeconds
}

object LflRegionPolicy {
    val supportedIds = setOf("uk", "de", "nl", "ch", "fr")

    fun regionFor(place: SavedPlace): RegionalRadarArea? =
        RegionalRadarAreas.forPoint(place.latitude, place.longitude)
            ?.takeIf { it.id in supportedIds }

    fun endpoint(regionId: String): String {
        require(regionId in supportedIds) { "Unsupported individual-flash region" }
        return "https://rain-alarm-lfl-feed.aaronjoakley55.workers.dev/v1/regions/$regionId"
    }
}

object LflFeedParser {
    private val json = Json
    private val pointId = Regex("[0-9a-f]{24}")
    private const val ATTRIBUTION =
        "Contains modified EUMETSAT Meteosat Third Generation LI-2-LFL data, 2026"
    private val topLevelKeys = setOf(
        "schemaVersion", "generationId", "regionId", "bounds", "intervalMinutes",
        "historyMinutes", "generatedAt", "observedFrom", "observedThrough", "completeThrough",
        "staleAfter", "status", "correction", "provenance", "attribution", "counts", "frames",
    )
    private val frameKeys = setOf("start", "end", "complete", "status", "count", "points")

    fun parse(bytes: ByteArray, expectedRegionId: String): LflLightningFeed {
        require(bytes.isNotEmpty() && bytes.size <= LflNetworkPolicy.maximumDecodedBytes) {
            "Individual-flash payload size is invalid"
        }
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        root.requireExactKeys(topLevelKeys, "document")
        require(root.int("schemaVersion") == 1) { "Unsupported individual-flash schema" }
        val generationId = root.string("generationId")
        require(Regex("[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}").matches(generationId)) {
            "Invalid individual-flash generation"
        }
        val regionId = root.string("regionId")
        require(regionId == expectedRegionId && regionId in LflRegionPolicy.supportedIds) {
            "Individual-flash region mismatch"
        }
        val area = requireNotNull(RegionalRadarAreas.all.firstOrNull { it.id == regionId })
        root.objectValue("bounds").also { bounds ->
            bounds.requireExactKeys(setOf("north", "west", "south", "east"), "bounds")
            require(bounds.double("north").toBits() == area.north.toBits() &&
                bounds.double("west").toBits() == area.west.toBits() &&
                bounds.double("south").toBits() == area.south.toBits() &&
                bounds.double("east").toBits() == area.east.toBits()) {
                "Individual-flash bounds do not match the selected radar region"
            }
        }
        require(root.int("intervalMinutes") == 5 && root.int("historyMinutes") == 90) {
            "Individual-flash cadence is unsupported"
        }
        Instant.parse(root.string("generatedAt"))
        val observedFrom = Instant.parse(root.string("observedFrom")).epochSecond
        val observedThrough = Instant.parse(root.string("observedThrough")).epochSecond
        val completeThrough = Instant.parse(root.string("completeThrough")).epochSecond
        val staleAfter = Instant.parse(root.string("staleAfter")).epochSecond
        require(observedThrough - observedFrom == 90L * 60L &&
            completeThrough == observedThrough && staleAfter == completeThrough + 12L * 60L) {
            "Individual-flash coverage window is invalid"
        }
        val status = parseTopStatus(root.string("status"))
        require(root.string("attribution") == ATTRIBUTION) {
            "Individual-flash attribution is missing"
        }
        root.objectValue("correction").also { correction ->
            correction.requireExactKeys(setOf("applied", "mode", "version"), "correction")
            require(correction.boolean("applied") == false && correction.string("mode") == "none" &&
                correction.string("version") == "raw-centroids-v1") {
                "Unsupported individual-flash correction"
            }
        }
        root.objectValue("provenance").also { provenance ->
            provenance.requireExactKeys(
                setOf(
                    "collectionId", "observationType", "sourceBatchMinutes",
                    "typicalPublicationLatencySeconds", "intervalSemantics", "pointFields",
                ),
                "provenance",
            )
            require(provenance.string("collectionId") == "EO:EUM:DAT:0691" &&
                provenance.int("sourceBatchMinutes") == 10 &&
                provenance.string("intervalSemantics") == "UTC half-open [start,end)" &&
                provenance.array("pointFields").map { it.jsonPrimitive.content } ==
                listOf("id", "observedAtEpochMs", "latitude", "longitude")) {
                "Unsupported individual-flash provenance"
            }
        }

        val frames = root.array("frames")
        require(frames.size == 18) { "Individual-flash frame count is invalid" }
        var expectedStart = observedFrom
        var rawPointCount = 0
        var completeFrameCount = 0
        var partialFrameCount = 0
        var unknownFrameCount = 0
        var validEmptyFrameCount = 0
        val deduplicated = LinkedHashMap<String, LflFlashPoint>()
        frames.forEach { element ->
            val frame = element.jsonObject
            frame.requireExactKeys(frameKeys, "frame")
            val start = Instant.parse(frame.string("start")).epochSecond
            val end = Instant.parse(frame.string("end")).epochSecond
            require(start == expectedStart && end == start + 5L * 60L) {
                "Individual-flash frames are not contiguous"
            }
            expectedStart = end
            val complete = frame.boolean("complete")
            val frameStatus = frame.string("status")
            require(frameStatus in setOf("valid", "valid-empty", "partial", "unknown")) {
                "Individual-flash frame status is invalid"
            }
            require(complete == (frameStatus == "valid" || frameStatus == "valid-empty")) {
                "Individual-flash frame completeness conflicts with status"
            }
            when (frameStatus) {
                "valid-empty" -> validEmptyFrameCount++
                "partial" -> partialFrameCount++
                "unknown" -> unknownFrameCount++
            }
            if (complete) completeFrameCount++
            val points = frame.array("points")
            require(frame.int("count") == points.size) { "Individual-flash frame count mismatch" }
            if (frameStatus == "valid-empty" || frameStatus == "unknown") {
                require(points.isEmpty()) { "Empty individual-flash frame contains points" }
            }
            if (frameStatus == "valid") require(points.isNotEmpty()) {
                "Non-empty individual-flash frame is empty"
            }
            var previousOrdering: Pair<Long, String>? = null
            points.forEach { pointElement ->
                val tuple = pointElement as? JsonArray
                    ?: throw IllegalArgumentException("Individual-flash point is not a tuple")
                require(tuple.size == 4) { "Individual-flash point tuple has the wrong size" }
                val id = tuple[0].jsonPrimitive.content
                val epochMs = tuple[1].jsonPrimitive.longOrNull
                    ?: throw IllegalArgumentException("Individual-flash time is invalid")
                val latitude = tuple[2].jsonPrimitive.doubleOrNull
                    ?: throw IllegalArgumentException("Individual-flash latitude is invalid")
                val longitude = tuple[3].jsonPrimitive.doubleOrNull
                    ?: throw IllegalArgumentException("Individual-flash longitude is invalid")
                require(pointId.matches(id) && latitude.isFinite() && longitude.isFinite() &&
                    latitude in area.south..area.north && longitude in area.west..area.east &&
                    epochMs >= start * 1_000L && epochMs < end * 1_000L) {
                    "Individual-flash point is outside its contract bounds"
                }
                val ordering = epochMs to id
                require(previousOrdering?.let { compareOrdering(it, ordering) <= 0 } != false) {
                    "Individual-flash points are not ordered"
                }
                previousOrdering = ordering
                val point = LflFlashPoint(id, epochMs, latitude, longitude)
                val existing = deduplicated.putIfAbsent(id, point)
                require(existing == null || existing == point) {
                    "Individual-flash identity has conflicting observations"
                }
                rawPointCount++
            }
        }
        require(expectedStart == observedThrough) { "Individual-flash frame window is incomplete" }
        root.objectValue("counts").also { counts ->
            counts.requireExactKeys(
                setOf(
                    "points", "frames", "completeFrames", "partialFrames", "unknownFrames",
                    "validEmptyFrames",
                ),
                "counts",
            )
            require(counts.int("points") == rawPointCount && counts.int("frames") == frames.size &&
                counts.int("completeFrames") == completeFrameCount &&
                counts.int("partialFrames") == partialFrameCount &&
                counts.int("unknownFrames") == unknownFrameCount &&
                counts.int("validEmptyFrames") == validEmptyFrameCount) {
                "Individual-flash aggregate counts are invalid"
            }
        }
        if (status == LflFeedStatus.VALID_EMPTY) require(rawPointCount == 0 && validEmptyFrameCount == 18)
        if (status == LflFeedStatus.VALID) require(rawPointCount > 0 && completeFrameCount == 18)
        if (status == LflFeedStatus.PARTIAL) require(partialFrameCount + unknownFrameCount > 0)
        return LflLightningFeed(
            generationId, regionId, status, observedFrom, observedThrough, completeThrough,
            staleAfter, deduplicated.values.sortedWith(compareBy(LflFlashPoint::observedAtEpochMs)
                .thenBy(LflFlashPoint::id)), ATTRIBUTION,
        )
    }

    private fun parseTopStatus(value: String): LflFeedStatus = when (value) {
        "valid" -> LflFeedStatus.VALID
        "valid-empty" -> LflFeedStatus.VALID_EMPTY
        "partial" -> LflFeedStatus.PARTIAL
        "stale" -> LflFeedStatus.STALE
        else -> throw IllegalArgumentException("Individual-flash document status is invalid")
    }

    private fun compareOrdering(first: Pair<Long, String>, second: Pair<Long, String>): Int =
        compareValuesBy(first, second, Pair<Long, String>::first, Pair<Long, String>::second)

    private fun JsonObject.requireExactKeys(expected: Set<String>, label: String) {
        require(keys == expected) { "Individual-flash $label fields are invalid" }
    }
    private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("Individual-flash $name is missing")
    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("Individual-flash $name is invalid")
    private fun JsonObject.double(name: String): Double = this[name]?.jsonPrimitive?.doubleOrNull
        ?.takeIf(Double::isFinite) ?: throw IllegalArgumentException("Individual-flash $name is invalid")
    private fun JsonObject.boolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("Individual-flash $name is invalid")
    private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject
        ?: throw IllegalArgumentException("Individual-flash $name is invalid")
    private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray
        ?: throw IllegalArgumentException("Individual-flash $name is invalid")
}

internal object LflNetworkPolicy {
    const val maximumCompressedBytes = 20 * 1024 * 1024
    const val maximumDecodedBytes = 32 * 1024 * 1024
    const val connectTimeoutMillis = 8_000
    const val readTimeoutMillis = 15_000
}

data class LflTransportResponse(
    val statusCode: Int,
    val contentType: String?,
    val contentEncoding: String?,
    val etag: String?,
    val generationId: String?,
    val completeThrough: String?,
    val advertisedStatus: String?,
    val body: ByteArray,
)

fun interface LflFeedTransport {
    suspend fun fetch(request: LflTransportRequest): LflTransportResponse
}

data class LflTransportRequest(val regionId: String, val etag: String?)

class CloudflareLflFeedTransport : LflFeedTransport {
    override suspend fun fetch(request: LflTransportRequest): LflTransportResponse =
        withContext(Dispatchers.IO) {
            val url = URL(LflRegionPolicy.endpoint(request.regionId))
            require(url.protocol == "https" &&
                url.host == "rain-alarm-lfl-feed.aaronjoakley55.workers.dev") {
                "Unexpected individual-flash host"
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = LflNetworkPolicy.connectTimeoutMillis
            connection.readTimeout = LflNetworkPolicy.readTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "RainAlarm/0.9")
            request.etag?.let { connection.setRequestProperty("If-None-Match", it) }
            try {
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return@withContext response(
                    connection, status, ByteArray(0),
                )
                if (status == 429 || status in 500..599) {
                    throw IOException("Individual-flash feed is temporarily unavailable")
                }
                if (status !in 200..299) throw IOException("Individual-flash feed returned HTTP $status")
                val declaredLength = connection.contentLengthLong
                require(declaredLength in -1..LflNetworkPolicy.maximumCompressedBytes.toLong()) {
                    "Individual-flash response is too large"
                }
                val compressed = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= LflNetworkPolicy.maximumCompressedBytes) {
                            "Individual-flash response is too large"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val body = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = gzip.read(buffer)
                            if (read < 0) break
                            require(output.size() + read <= LflNetworkPolicy.maximumDecodedBytes) {
                                "Individual-flash document is too large"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                } else compressed
                currentCoroutineContext().ensureActive()
                response(connection, status, body)
            } finally {
                connection.disconnect()
            }
        }

    private fun response(
        connection: HttpURLConnection,
        status: Int,
        body: ByteArray,
    ) = LflTransportResponse(
        status,
        connection.contentType,
        connection.contentEncoding,
        connection.getHeaderField("ETag"),
        connection.getHeaderField("X-Rain-Generation"),
        connection.getHeaderField("X-Rain-Complete-Through"),
        connection.getHeaderField("X-Rain-Status"),
        body,
    )
}

class LflLightningRepository(
    private val transport: LflFeedTransport = CloudflareLflFeedTransport(),
) {
    private data class CacheEntry(
        val feed: LflLightningFeed,
        val etag: String?,
        val validatedAtEpochSeconds: Long,
    )
    private val cached = ConcurrentHashMap<String, CacheEntry>()
    private val mutex = Mutex()

    suspend fun feed(place: SavedPlace, nowEpochSeconds: Long, force: Boolean = false): LflLightningFeed {
        val region = LflRegionPolicy.regionFor(place)
            ?: throw IOException("Individual flashes are unavailable outside supported radar regions")
        return feed(region.id, nowEpochSeconds, force)
    }

    suspend fun feed(regionId: String, nowEpochSeconds: Long, force: Boolean = false): LflLightningFeed =
        mutex.withLock {
            val prior = cached[regionId]
            // Normal recomposition/navigation in the same source minute reuses verified state;
            // explicit refresh still performs a conditional request.
            if (!force && prior != null && prior.feed.isFreshAt(nowEpochSeconds) &&
                nowEpochSeconds - prior.validatedAtEpochSeconds in 0L until 60L) {
                return@withLock prior.feed
            }
            try {
                val response = transport.fetch(LflTransportRequest(regionId, prior?.etag))
                when (response.statusCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> requireNotNull(prior) {
                        "Individual-flash feed returned 304 without cached data"
                    }.also {
                        it.feed.requireUsable(nowEpochSeconds)
                        cached[regionId] = it.copy(validatedAtEpochSeconds = nowEpochSeconds)
                    }.feed
                    in 200..299 -> {
                        require(response.contentType?.substringBefore(';')
                            ?.equals("application/json", ignoreCase = true) == true) {
                            "Individual-flash feed returned unexpected content"
                        }
                        require(response.contentEncoding.equals("gzip", ignoreCase = true)) {
                            "Individual-flash feed was not gzip encoded"
                        }
                        val responseEtag = requireNotNull(response.etag?.takeIf(String::isNotBlank)) {
                            "Individual-flash feed ETag is missing"
                        }
                        val parsed = LflFeedParser.parse(response.body, regionId)
                        require(response.generationId == parsed.generationId) {
                            "Individual-flash generation header is invalid"
                        }
                        require(response.completeThrough?.let(Instant::parse)?.epochSecond ==
                            parsed.completeThroughEpochSeconds) {
                            "Individual-flash completion header is invalid"
                        }
                        require(response.advertisedStatus == parsed.status.wireValue()) {
                            "Individual-flash status header is invalid"
                        }
                        parsed.requireUsable(nowEpochSeconds)
                        if (prior != null && parsed.completeThroughEpochSeconds <
                            prior.feed.completeThroughEpochSeconds) {
                            prior.feed.requireUsable(nowEpochSeconds)
                        } else {
                            cached[regionId] = CacheEntry(parsed, responseEtag, nowEpochSeconds)
                            parsed
                        }
                    }
                    else -> throw IOException("Individual-flash feed returned HTTP ${response.statusCode}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                prior?.feed?.takeIf { it.isFreshAt(nowEpochSeconds) } ?: throw failure
            }
        }

    internal fun cached(regionId: String): LflLightningFeed? = cached[regionId]?.feed

    private fun LflLightningFeed.requireUsable(nowEpochSeconds: Long): LflLightningFeed {
        if (status == LflFeedStatus.PARTIAL) throw IOException("Individual-flash feed is partial")
        if (status == LflFeedStatus.STALE || nowEpochSeconds >= staleAfterEpochSeconds) {
            throw IOException("Individual-flash feed is stale")
        }
        return this
    }

    private fun LflFeedStatus.wireValue(): String = when (this) {
        LflFeedStatus.VALID -> "valid"
        LflFeedStatus.VALID_EMPTY -> "valid-empty"
        LflFeedStatus.PARTIAL -> "partial"
        LflFeedStatus.STALE -> "stale"
    }
}

object LflLightningRepositoryProvider {
    @Volatile private var instance: LflLightningRepository? = null

    fun get(): LflLightningRepository = instance ?: synchronized(this) {
        instance ?: LflLightningRepository().also { instance = it }
    }
}

/** All preview-only visual tuning values live here and are intentionally not user preferences. */
object LflLightningVisualStyle {
    const val visualIntervalSeconds = 150L
    const val lifetimeSeconds = 1_200L
    /** The only preview marker-size tuning knob: fractional reduction for each zoom below 10. */
    const val zoomScaleReductionPerLevel = 0.12f
    const val coreDiameterDp = 5f
    const val haloDiameterDp = 11f
    const val innerGlowDiameterDp = 2.4f
    const val pinpointDiameterDp = 1.1f
    const val haloOpacityMultiplier = 0.34f
    const val innerGlowOpacityMultiplier = 0.72f
    const val freshColorArgb = 0xFFFFE45C.toInt()
    const val oldestColorArgb = 0xFFA9272D.toInt()
    const val innerGlowColorArgb = 0xFFFFFBE8.toInt()
    const val pinpointColorArgb = 0xFFFFFFFA.toInt()
    const val visibleCohorts = 8

    private const val referenceZoom = 10f
    private const val minimumZoomScale = 0.25f
    private val ageColorsArgb = intArrayOf(
        freshColorArgb,
        0xFFFFD33F.toInt(),
        0xFFFFBD2E.toInt(),
        0xFFFFA21F.toInt(),
        0xFFF78016.toInt(),
        0xFFE85F18.toInt(),
        0xFFCF3F22.toInt(),
        oldestColorArgb,
    )
    private val coreBlurStops = floatArrayOf(
        0.08f, 0.10f, 0.14f, 0.19f, 0.25f, 0.33f, 0.42f, 0.52f,
    )

    fun zoomScale(zoom: Float): Float = if (zoom >= referenceZoom) 1f else {
        (1f - zoomScaleReductionPerLevel * (referenceZoom - zoom))
            .coerceAtLeast(minimumZoomScale)
    }

    /** First zoom at which the linear rule reaches its fixed safety floor. */
    fun minimumScaleZoom(): Float =
        referenceZoom - (1f - minimumZoomScale) / zoomScaleReductionPerLevel

    /** Continuous fade from visual cohort birth through exact removal at 20 minutes. */
    fun opacity(ageSeconds: Double): Float =
        (1.0 - ageSeconds.coerceAtLeast(0.0) / lifetimeSeconds.toDouble())
            .coerceIn(0.0, 1.0).toFloat()

    /** Linear RGB interpolation through the established 2.5-minute colour tuning stops. */
    fun color(ageSeconds: Double): Int = interpolateArgb(ageColorsArgb, ageSeconds)

    /** Linear interpolation through the established softening stops. */
    fun coreBlur(ageSeconds: Double): Float = interpolate(coreBlurStops, ageSeconds)

    /** Includes the terminal 20-minute stop used by native MapLibre interpolation. */
    fun colorStopArgb(index: Int): Int =
        ageColorsArgb[index.coerceIn(0, visibleCohorts - 1)]

    /** Includes the terminal 20-minute stop used by native MapLibre interpolation. */
    fun coreBlurStop(index: Int): Float =
        coreBlurStops[index.coerceIn(0, visibleCohorts - 1)]

    private fun interpolate(values: FloatArray, ageSeconds: Double): Float {
        val position = (ageSeconds.coerceIn(0.0, lifetimeSeconds.toDouble()) /
            visualIntervalSeconds.toDouble())
        val lower = position.toInt().coerceIn(0, visibleCohorts - 1)
        val upper = (lower + 1).coerceAtMost(visibleCohorts - 1)
        val fraction = (position - lower).coerceIn(0.0, 1.0).toFloat()
        return values[lower] + (values[upper] - values[lower]) * fraction
    }

    private fun interpolateArgb(values: IntArray, ageSeconds: Double): Int {
        val position = (ageSeconds.coerceIn(0.0, lifetimeSeconds.toDouble()) /
            visualIntervalSeconds.toDouble())
        val lower = position.toInt().coerceIn(0, visibleCohorts - 1)
        val upper = (lower + 1).coerceAtMost(visibleCohorts - 1)
        val fraction = (position - lower).coerceIn(0.0, 1.0)
        fun channel(shift: Int): Int {
            val start = values[lower] ushr shift and 0xff
            val end = values[upper] ushr shift and 0xff
            return (start + (end - start) * fraction).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }
}

data class LflRenderedFlash(
    val point: LflFlashPoint,
    val birthEpochSeconds: Long,
    val ageSeconds: Double,
    val opacity: Float,
    val haloOpacity: Float,
    val innerGlowOpacity: Float,
    val colorArgb: Int,
    val coreBlur: Float,
)

data class LflLightningSourceFlash(
    val point: LflFlashPoint,
    val birthOffsetSeconds: Double,
)

/**
 * The active radar renderer context that constrains individual-flash visibility.
 *
 * The context intentionally contains no coverage-mask appearance setting: a disabled visual
 * scrim must not make flashes outside MeteoGroup's nominal radar footprint visible again.
 */
data class LflLightningCoverageContext(
    val radarProvider: RadarProviderKind?,
    val activeRadarAreaId: String?,
) {
    val identity: String = buildString {
        append(radarProvider?.name ?: "NO_RADAR_PROVIDER")
        append(':')
        append(activeRadarAreaId ?: "NO_RADAR_AREA")
        append(':')
        append(
            if (radarProvider == RadarProviderKind.METEOGROUP_REGIONAL) {
                MeteoNominalCoverage.GEOMETRY_VERSION
            } else {
                "UNRESTRICTED"
            },
        )
    }

    companion object {
        val UNRESTRICTED = LflLightningCoverageContext(null, null)
    }
}

/** Uses the same nominal geometry as the MeteoGroup map scrim, but as real data filtering. */
object LflLightningCoveragePolicy {
    fun includes(context: LflLightningCoverageContext, point: LflFlashPoint): Boolean {
        if (context.radarProvider != RadarProviderKind.METEOGROUP_REGIONAL) return true
        val areaId = context.activeRadarAreaId ?: return true
        return MeteoNominalCoverage.covers(
            areaId,
            GeoPoint(point.latitude, point.longitude),
        ) ?: true
    }
}

/** Stable GeoJSON payload for one feed generation; selected time never changes this object. */
data class LflLightningSource(
    val generationId: String,
    val regionId: String,
    val coverageIdentity: String,
    val baselineEpochSeconds: Long,
    val observedFromEpochSeconds: Long,
    val flashes: List<LflLightningSourceFlash>,
) {
    val identity: String = "$generationId:$regionId:$coverageIdentity:$baselineEpochSeconds"
}

data class LflLightningPresentation(
    val source: LflLightningSource,
    val referenceEpochSeconds: Double,
    val referenceOffsetSeconds: Double,
    val covered: Boolean,
)

object LflLightningPresentationPolicy {
    fun referenceEpochSeconds(
        @Suppress("UNUSED_PARAMETER") feed: LflLightningFeed,
        selectedEpochSeconds: Double,
    ): Double = selectedEpochSeconds

    fun source(
        feed: LflLightningFeed,
        coverageContext: LflLightningCoverageContext = LflLightningCoverageContext.UNRESTRICTED,
        cancellationCheck: () -> Unit = {},
    ): LflLightningSource {
        val intervalMs = LflLightningVisualStyle.visualIntervalSeconds * 1_000L
        val baseline = feed.observedFromEpochSeconds
        val flashes = feed.points.mapIndexedNotNull { index, point ->
            if (index % 128 == 0) cancellationCheck()
            if (!LflLightningCoveragePolicy.includes(coverageContext, point)) {
                return@mapIndexedNotNull null
            }
            val birthEpochMs = Math.floorDiv(
                point.observedAtEpochMs + intervalMs - 1L,
                intervalMs,
            ) * intervalMs
            LflLightningSourceFlash(
                point,
                birthEpochMs / 1_000.0 - baseline.toDouble(),
            )
        }
        return LflLightningSource(
            feed.generationId,
            feed.regionId,
            coverageContext.identity,
            baseline,
            feed.observedFromEpochSeconds,
            flashes,
        )
    }

    fun frame(
        source: LflLightningSource,
        selectedEpochSeconds: Double,
    ): LflLightningPresentation {
        val reference = selectedEpochSeconds
        return LflLightningPresentation(
            source = source,
            referenceEpochSeconds = reference,
            referenceOffsetSeconds = reference - source.baselineEpochSeconds,
            covered = reference - LflLightningVisualStyle.lifetimeSeconds >=
                source.observedFromEpochSeconds,
        )
    }

    fun at(feed: LflLightningFeed, selectedEpochSeconds: Long): LflLightningPresentation =
        frame(source(feed), selectedEpochSeconds.toDouble())

    fun at(feed: LflLightningFeed, selectedEpochSeconds: Double): LflLightningPresentation =
        frame(source(feed), selectedEpochSeconds)

    fun renderedFlashes(
        presentation: LflLightningPresentation,
        cancellationCheck: () -> Unit = {},
    ): List<LflRenderedFlash> {
        if (!presentation.covered) return emptyList()
        return presentation.source.flashes.mapIndexedNotNull { index, sourceFlash ->
            if (index % 128 == 0) cancellationCheck()
            val birthEpochSeconds = presentation.source.baselineEpochSeconds +
                sourceFlash.birthOffsetSeconds.toLong()
            val ageSeconds = presentation.referenceOffsetSeconds -
                sourceFlash.birthOffsetSeconds
            if (ageSeconds < 0.0 || ageSeconds >= LflLightningVisualStyle.lifetimeSeconds) {
                return@mapIndexedNotNull null
            }
            val opacity = LflLightningVisualStyle.opacity(ageSeconds)
            LflRenderedFlash(
                sourceFlash.point, birthEpochSeconds, ageSeconds, opacity,
                opacity * LflLightningVisualStyle.haloOpacityMultiplier,
                opacity * LflLightningVisualStyle.innerGlowOpacityMultiplier,
                LflLightningVisualStyle.color(ageSeconds),
                LflLightningVisualStyle.coreBlur(ageSeconds),
            )
        }
    }
}
