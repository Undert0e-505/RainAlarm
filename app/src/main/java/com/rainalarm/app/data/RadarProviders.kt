package com.rainalarm.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.graphics.get
import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.RadarResolutionTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RadarProviderKind {
    METEOGROUP_REGIONAL,
    OPEN_RAINVIEWER,
}

object RainViewerSnowPreference {
    fun decode(stored: Boolean?): Boolean = stored ?: false
}

object RainViewerSnowPolicy {
    fun effective(requestedProvider: RadarProviderKind, enabled: Boolean): Boolean =
        requestedProvider == RadarProviderKind.OPEN_RAINVIEWER && enabled
}

enum class RadarPlaybackSpeed(val multiplier: Float, val label: String) {
    HALF(0.5f, "0.5×"), NORMAL(1f, "1×"), DOUBLE(2f, "2×"), QUADRUPLE(4f, "4×"),
}

enum class RadarMapLayer(val label: String) {
    OFF("Off"), WIND("Wind"), LIGHTNING("Lightning"), FOG("Fog");

    companion object {
        fun decode(raw: String?): RadarMapLayer = entries.firstOrNull { it.name == raw } ?: OFF
    }
}

enum class NowWeatherMetric(val label: String) {
    TEMPERATURE("Temperature"), PRESSURE("Pressure"), HUMIDITY("Humidity"), UV_INDEX("UV Index"), WIND("Wind")
}

object NowWeatherMetricPreference {
    // Missing settings get all metrics; an explicitly stored subset (including empty) is preserved.
    private val defaults = NowWeatherMetric.entries.toSet()
    fun decode(raw: String?): Set<NowWeatherMetric> = if (raw == null) defaults
        else raw.split(',').mapNotNull { name -> NowWeatherMetric.entries.firstOrNull { it.name == name } }
            .toSet().let { parsed -> if (raw.isNotBlank() && parsed.isEmpty()) defaults else parsed }
    fun encode(metrics: Set<NowWeatherMetric>): String = NowWeatherMetric.entries
        .filter(metrics::contains).joinToString(",") { it.name }
}

enum class AppearanceMode(val label: String) {
    DARK("Dark"), LIGHT("Light"), FOLLOW_SYSTEM("Follow system");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        FOLLOW_SYSTEM -> systemDark
    }

    companion object {
        fun decode(value: String?): AppearanceMode = entries.firstOrNull { it.name == value } ?: DARK
    }
}

object RadarPlaybackSpeedPreference {
    fun encode(speed: RadarPlaybackSpeed): String = speed.name
    fun decode(value: String?): RadarPlaybackSpeed =
        RadarPlaybackSpeed.entries.firstOrNull { it.name == value } ?: RadarPlaybackSpeed.DOUBLE
}

data class RadarProviderSelection(
    val requested: RadarProviderKind,
    val active: RadarProviderKind,
    val fallbackMessage: String? = null,
)

private val Context.radarSettingsDataStore by preferencesDataStore(name = "radar_settings")

class RadarSettingsRepository(private val context: Context) {
    private val providerKey = stringPreferencesKey("radar_provider")
    private val playbackSpeedKey = stringPreferencesKey("radar_playback_speed")
    private val appAppearanceKey = stringPreferencesKey("app_appearance")
    private val mapAppearanceKey = stringPreferencesKey("map_appearance")
    private val compassAppearanceKey = stringPreferencesKey("now_compass_appearance")
    private val graphAppearanceKey = stringPreferencesKey("now_graph_appearance")
    private val mapLayerKey = stringPreferencesKey("map_layer")
    private val windArrowScaleKey = floatPreferencesKey("wind_arrow_scale")
    private val nowMetricsKey = stringPreferencesKey("now_weather_metrics")
    private val showLikelySnowKey = booleanPreferencesKey("rainviewer_show_likely_snow")

    val provider: Flow<RadarProviderKind> = context.radarSettingsDataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences -> RadarProviderPreference.decode(preferences[providerKey]) }

    val playbackSpeed: Flow<RadarPlaybackSpeed> = context.radarSettingsDataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences -> RadarPlaybackSpeedPreference.decode(preferences[playbackSpeedKey]) }

    val appAppearance: Flow<AppearanceMode> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { AppearanceMode.decode(it[appAppearanceKey]) }

    val mapAppearance: Flow<AppearanceMode> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { AppearanceMode.decode(it[mapAppearanceKey]) }

    val compassAppearance: Flow<NowCardAppearance> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { NowCardAppearance.decode(it[compassAppearanceKey]) }

    val graphAppearance: Flow<NowCardAppearance> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { NowCardAppearance.decode(it[graphAppearanceKey]) }

    val mapLayer: Flow<RadarMapLayer> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { RadarMapLayer.decode(it[mapLayerKey]) }

    val windArrowScale: Flow<Float> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { WindArrowSizePreference.decode(it[windArrowScaleKey]) }

    val nowMetrics: Flow<Set<NowWeatherMetric>> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { NowWeatherMetricPreference.decode(it[nowMetricsKey]) }

    val showLikelySnow: Flow<Boolean> = context.radarSettingsDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { RainViewerSnowPreference.decode(it[showLikelySnowKey]) }

    suspend fun selectedProvider(): RadarProviderKind = provider.first()
    suspend fun selectedShowLikelySnow(): Boolean = showLikelySnow.first()

    suspend fun setProvider(provider: RadarProviderKind) {
        context.radarSettingsDataStore.edit { it[providerKey] = RadarProviderPreference.encode(provider) }
    }

    suspend fun setPlaybackSpeed(speed: RadarPlaybackSpeed) {
        context.radarSettingsDataStore.edit { it[playbackSpeedKey] = RadarPlaybackSpeedPreference.encode(speed) }
    }

    suspend fun setAppAppearance(mode: AppearanceMode) {
        context.radarSettingsDataStore.edit { it[appAppearanceKey] = mode.name }
    }

    suspend fun setMapAppearance(mode: AppearanceMode) {
        context.radarSettingsDataStore.edit { it[mapAppearanceKey] = mode.name }
    }

    suspend fun setCompassAppearance(mode: NowCardAppearance) {
        context.radarSettingsDataStore.edit { it[compassAppearanceKey] = mode.name }
    }

    suspend fun setGraphAppearance(mode: NowCardAppearance) {
        context.radarSettingsDataStore.edit { it[graphAppearanceKey] = mode.name }
    }

    suspend fun setMapLayer(layer: RadarMapLayer) {
        context.radarSettingsDataStore.edit { it[mapLayerKey] = layer.name }
    }

    suspend fun setWindArrowScale(scale: Float) {
        context.radarSettingsDataStore.edit {
            it[windArrowScaleKey] = WindArrowSizePreference.decode(scale)
        }
    }

    suspend fun setNowMetricVisible(metric: NowWeatherMetric, visible: Boolean) {
        context.radarSettingsDataStore.edit { preferences ->
            val updated = NowWeatherMetricPreference.decode(preferences[nowMetricsKey]).toMutableSet()
            if (visible) updated.add(metric) else updated.remove(metric)
            preferences[nowMetricsKey] = NowWeatherMetricPreference.encode(updated)
        }
    }

    suspend fun setShowLikelySnow(enabled: Boolean) {
        context.radarSettingsDataStore.edit { it[showLikelySnowKey] = enabled }
    }
}

object RadarProviderPreference {
    fun encode(provider: RadarProviderKind): String = provider.name

    fun decode(stored: String?): RadarProviderKind = stored?.let { value ->
        RadarProviderKind.entries.firstOrNull { it.name == value }
    } ?: RadarProviderKind.METEOGROUP_REGIONAL
}

data class RegionalRadarArea(
    val id: String,
    val displayName: String,
    val north: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val rasterWidth: Int,
    val rasterHeight: Int,
    val velocityScale: Float,
    val pixelWidthMetres: Double,
    val pixelHeightMetres: Double,
    val pixelOffsetX: Double,
    val pixelOffsetY: Double,
    val projection: String,
) {
    val bounds = GeoQuad(
        GeoPoint(north, west),
        GeoPoint(north, east),
        GeoPoint(south, east),
        GeoPoint(south, west),
    )

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east
}

object RegionalRadarAreas {
    // Clean-room configuration derived from the public raster geometry needed to place each feed.
    val all = listOf(
        RegionalRadarArea("uk", "UK & Ireland", 59.737642, -14.677787, 45.575355, 5.827836, 583, 767, 8f,
            1999.2506234920268, 1999.2506234920268, -323099.15625, 1194687.75,
            "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 +y_0=-100000 +ellps=airy +datum=OSGB36 +units=m +no_defs"),
        RegionalRadarArea("de", "Germany", 55.0984291, 1.91780433, 46.9658661, 15.900782, 460, 460, 11f,
            2000.0, 2000.0, 0.0, 0.0,
            "+proj=stere +lat_0=90 +lat_ts=60 +lon_0=10 +a=6370000 +b=6370000 +y_0=3749618.296521171 +x_0=532461.7056238621"),
        RegionalRadarArea("nl", "Netherlands & Benelux", 55.860496, 0.0, 48.781428, 10.856639, 700, 765, 8f,
            1074.9917354982683, 1072.4027123136912, 0.0, -3911890.1729054004,
            "+proj=stere +lat_0=90 +lat_ts=90 +lon_0=0 +datum=WGS84 +units=m +no_defs"),
        RegionalRadarArea("ch", "Switzerland", 49.38, 2.69, 43.62, 12.46, 710, 640, 8f,
            1001.6589019423421, 1002.217710090751, 255052.3657792788, 480441.57420079992,
            "+proj=somerc +lat_0=46.95240555555556 +lon_0=7.439583333333333 +k_0=1 +x_0=600000 +y_0=200000 +ellps=bessel +towgs84=674.374,15.056,405.346,0,0,0,0 +units=m +no_defs"),
        RegionalRadarArea("fr", "France", 53.467973, -9.518992, 39.792072, 13.734322, 790, 724, 8f,
            2149.792377130344, 2143.19267210415, -1079854.7058729, -4332668.5727059552,
            "+proj=stere +lat_0=90 +lat_ts=90 +lon_0=5 +datum=WGS84 +units=m +no_defs"),
    )

    /**
     * Feed bounding boxes overlap well beyond national borders. Prefer the feed whose footprint
     * centre is nearest in normalized box coordinates, then use the ID as a stable tie-breaker.
     * This is a coverage heuristic, not an administrative-border lookup: coastal/remote overlap
     * boundaries remain approximate, but a UK point near the North Sea is no longer sent to NL.
     */
    fun forPoint(latitude: Double, longitude: Double): RegionalRadarArea? = all
        .filter { it.contains(latitude, longitude) }
        .minWithOrNull(compareBy<RegionalRadarArea> { area ->
            val eastFraction = (longitude - area.west) / (area.east - area.west) - 0.5
            val northFraction = (latitude - area.south) / (area.north - area.south) - 0.5
            eastFraction * eastFraction + northFraction * northFraction
        }.thenBy { it.id })
}

data class RegionalManifestFrame(
    val imageFilename: String,
    val velocityFilename: String?,
    val timestamp: Long,
    val modifiedAt: Long,
    val forecast: Boolean,
)

object RegionalManifestParser {
    private val timeElement = Regex("<Time\\s+([^>]*?)/?>", RegexOption.IGNORE_CASE)
    private val attribute = Regex("([A-Za-z]+)=\"([^\"]*)\"")
    private val safeRelativePath = Regex("(?:observation|forecast_[0-9]{12})/[A-Za-z0-9_.-]+\\.jpg")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")

    fun parse(xml: String): List<RegionalManifestFrame> {
        require(xml.length <= 256 * 1024) { "Regional radar manifest is too large" }
        val frames = timeElement.findAll(xml).mapNotNull { match ->
            val values = attribute.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val image = values["imageFilename"] ?: return@mapNotNull null
            require(safeRelativePath.matches(image)) { "Regional radar manifest contains an invalid image path" }
            val velocity = values["velocityFilename"]?.takeIf(String::isNotBlank)?.also {
                require(safeRelativePath.matches(it)) { "Regional radar manifest contains an invalid velocity path" }
            }
            RegionalManifestFrame(
                imageFilename = image,
                velocityFilename = velocity,
                timestamp = parseTime(requireNotNull(values["dtg"])),
                modifiedAt = parseTime(requireNotNull(values["mtime"])),
                forecast = values["forecast"] == "1",
            )
        }.sortedBy { it.timestamp }.distinctBy { it.timestamp }.toList()
        require(frames.size >= 2) { "Regional radar manifest has fewer than two frames" }
        require(frames.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp }) {
            "Regional radar timestamps are not ordered"
        }
        require(frames.any { !it.forecast }) { "Regional radar manifest has no observations" }
        return frames
    }

    private fun parseTime(value: String): Long = OffsetDateTime.parse(value, timestampFormatter).toEpochSecond()
}

interface RegionalRadarEndpoint {
    suspend fun manifest(area: RegionalRadarArea): String
    suspend fun image(area: RegionalRadarArea, relativePath: String): ByteArray
}

class MeteoGroupRegionalEndpoint : RegionalRadarEndpoint {
    private val root = "https://cdn.meteogroup.de/images/mapengine/rain2.0"

    override suspend fun manifest(area: RegionalRadarArea): String =
        fetch("$root/rad_${area.id}/images.xml", "text/xml", 256 * 1024).decodeToString()

    override suspend fun image(area: RegionalRadarArea, relativePath: String): ByteArray {
        require(!relativePath.contains("..") && !relativePath.startsWith('/')) { "Invalid regional radar path" }
        return fetch("$root/rad_${area.id}/$relativePath", "image/jpeg", 4 * 1024 * 1024)
    }

    private suspend fun fetch(urlString: String, contentType: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val url = URL(urlString)
            require(url.protocol == "https" && url.host == "cdn.meteogroup.de") { "Unexpected regional radar host" }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", contentType)
            connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
            try {
                val response = connection.responseCode
                if (response == 429 || response in 500..599) throw java.io.IOException("Regional radar is temporarily unavailable")
                check(response in 200..299) { "Regional radar returned $response" }
                require(connection.contentType?.substringBefore(';')?.equals(contentType, ignoreCase = true) == true) {
                    "Regional radar returned unexpected content"
                }
                require(connection.contentLengthLong in -1..maxBytes.toLong()) { "Regional radar response is too large" }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= maxBytes) { "Regional radar response is too large" }
                        output.write(buffer, 0, read)
                    }
                    currentCoroutineContext().ensureActive()
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
}

@SuppressLint("LogNotTimber") // Provider failures must remain visible in a plain adb logcat capture.
class MeteoGroupRadarSessionLoader(
    private val endpoint: RegionalRadarEndpoint = MeteoGroupRegionalEndpoint(),
) {
    suspend fun load(
        place: SavedPlace,
        maxFrames: Int? = null,
        mode: RadarLoadMode = RadarLoadMode.SCREEN_TWO_TIER,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): RadarSession = coroutineScope {
        val area = requireNotNull(RegionalRadarAreas.forPoint(place.latitude, place.longitude)) {
            "No MeteoGroup regional radar covers ${place.name}"
        }
        val parsed = RegionalManifestParser.parse(endpoint.manifest(area))
        val latestObservation = parsed.indexOfLast { !it.forecast }
        require(latestObservation >= 0) { "Regional radar has no current observation" }
        // A delayed observation may be much older than wall-clock Now. Keep
        // any supplied forecast frames through real now+60 when present.
        val horizon = maxOf(parsed[latestObservation].timestamp, Instant.now().epochSecond) + 60 * 60
        val eligible = parsed.filterIndexed { index, frame -> index <= latestObservation || frame.timestamp <= horizon }
        val selected = maxFrames?.let { count ->
            val observationTail = eligible.filter { !it.forecast }.takeLast(count.coerceAtLeast(2))
            observationTail + eligible.filter { it.forecast }
        } ?: eligible
        val currentTimestamp = parsed[latestObservation].timestamp
        Log.i("RainRadarProvider", "MeteoGroup area=${area.id} manifest=${parsed.size} selected=${selected.size}")
        val completed = AtomicInteger(0)
        val compressedTotal = AtomicLong(0)
        val retainRenderCache = mode == RadarLoadMode.SCREEN_TWO_TIER
        val total = selected.sumOf {
            1 + if (it.velocityFilename != null && shouldLoadRegionalVelocity(mode, it, currentTimestamp)) 1 else 0
        }
        onProgress(0, total)
        val downloaded = HashMap<Long, Pair<LegacyCompressedFrame, RadarPointSample>>(selected.size)
        val dispatcher = Dispatchers.IO.limitedParallelism(MAX_DOWNLOAD_CONCURRENCY)
        for (batch in LegacyFramePriority.boundedBatches(selected, MAX_DOWNLOAD_CONCURRENCY)) {
            val batchResults = coroutineScope {
                batch.map { metadata ->
                    async(dispatcher) {
                        val radarBytes = endpoint.image(area, metadata.imageFilename)
                        currentCoroutineContext().ensureActive()
                        validateJpeg(radarBytes, area)
                        if (retainRenderCache) accountBytes(compressedTotal, radarBytes.size)
                        withContext(Dispatchers.Main.immediate) { onProgress(completed.incrementAndGet(), total) }
                        val point = sampleSelectedPoint(radarBytes, area, place)
                        val needsVelocity = shouldLoadRegionalVelocity(mode, metadata, currentTimestamp)
                        val downloadedVelocity = metadata.velocityFilename?.takeIf { needsVelocity }?.let { filename ->
                            endpoint.image(area, filename).also { bytes ->
                                currentCoroutineContext().ensureActive()
                                validateJpeg(bytes, area)
                                if (retainRenderCache) accountBytes(compressedTotal, bytes.size)
                                withContext(Dispatchers.Main.immediate) {
                                    onProgress(completed.incrementAndGet(), total)
                                }
                            }
                        }
                        val localVelocity = downloadedVelocity?.let { sampleSelectedVelocity(it, area, place) }
                        val frame = RainViewerFrame(metadata.timestamp, metadata.imageFilename, metadata.forecast)
                        LegacyCompressedFrame(
                            -1,
                            frame,
                            if (retainRenderCache) radarBytes else byteArrayOf(),
                            downloadedVelocity?.takeIf { retainRenderCache },
                        ) to
                            RadarPointSample(
                                metadata.timestamp,
                                metadata.forecast,
                                point.atPlace,
                                point.minimum,
                                point.maximum,
                                localVelocity?.first,
                                localVelocity?.second,
                                point.patch,
                            )
                    }
                }.map { it.await() }
            }
            batchResults.forEach { downloaded[it.first.frame.time] = it }
        }
        val orderedPairs = selected.map { requireNotNull(downloaded[it.timestamp]) }
        val archiveFrames = orderedPairs.mapIndexed { index, pair -> pair.first.copy(id = index) }
        val archive = LegacyRadarArchive(
            frames = archiveFrames,
            pointSamples = orderedPairs.map { it.second },
            totalCompressedBytes = compressedTotal.get(),
        )
        Log.i(
            "RainRadarProvider",
            "MeteoGroup area=${area.id} compact session ready frames=${archive.frames.size} bytes=${archive.totalCompressedBytes}",
        )
        RadarSession(
            place = place,
            regional = null,
            detail = null,
            motion = null,
            pairMotions = List((archive.frames.size - 1).coerceAtLeast(0)) { null },
            providerSelection = RadarProviderSelection(
                RadarProviderKind.METEOGROUP_REGIONAL,
                RadarProviderKind.METEOGROUP_REGIONAL,
            ),
            region = area,
            legacyArchive = archive,
        )
    }

    /** Forecast brackets use their left frame's local velocity even in point-only analysis. */
    internal fun shouldLoadRegionalVelocity(
        mode: RadarLoadMode,
        frame: RegionalManifestFrame,
        latestObservationTimestamp: Long,
    ): Boolean = mode == RadarLoadMode.SCREEN_TWO_TIER ||
        frame.timestamp == latestObservationTimestamp || frame.forecast

    private fun validateJpeg(bytes: ByteArray, area: RegionalRadarArea) {
        require(bytes.isNotEmpty()) { "Regional radar image was empty" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType == "image/jpeg") { "Regional radar resource is not JPEG" }
        require(options.outWidth == area.rasterWidth && options.outHeight == area.rasterHeight) {
            "Regional radar dimensions changed unexpectedly"
        }
    }

    private data class PointIntensity(
        val minimum: Float,
        val atPlace: Float,
        val maximum: Float,
        val patch: com.rainalarm.app.domain.RegionalPointPatch,
    )

    @Suppress("DEPRECATION")
    private fun sampleSelectedPoint(bytes: ByteArray, area: RegionalRadarArea, place: SavedPlace): PointIntensity {
        val pixel = requireNotNull(
            com.rainalarm.app.domain.RegionalProjection.pixelFor(area, place.latitude, place.longitude),
        ) { "Selected point is outside the regional raster" }
        val centerX = kotlin.math.floor(pixel.x).toInt().coerceIn(0, area.rasterWidth - 1)
        val centerY = kotlin.math.floor(pixel.y).toInt().coerceIn(0, area.rasterHeight - 1)
        // At most velocityScale source pixels are displaced during a frame interval. A small
        // symmetric patch supports every minute's GLES-equivalent warped point lookup.
        val radius = kotlin.math.ceil(area.velocityScale.toDouble()).toInt() + 2
        val rect = Rect(
            (centerX - radius).coerceAtLeast(0),
            (centerY - radius).coerceAtLeast(0),
            (centerX + radius + 1).coerceAtMost(area.rasterWidth),
            (centerY + radius + 1).coerceAtMost(area.rasterHeight),
        )
        try {
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            return try {
                val bitmap = decoder.decodeRegion(
                    rect,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: error("Regional radar point sample could not be decoded")
                try {
                    require(bitmap.width == rect.width() && bitmap.height == rect.height())
                    var minimum = 1f
                    var maximum = 0f
                    val values = FloatArray(bitmap.width * bitmap.height)
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val intensity = ((bitmap[x, y] shr 16) and 0xff) / 255f
                        if (kotlin.math.abs(rect.left + x - centerX) <= 2 &&
                            kotlin.math.abs(rect.top + y - centerY) <= 2) {
                            minimum = minOf(minimum, intensity)
                            maximum = maxOf(maximum, intensity)
                        }
                        values[y * bitmap.width + x] = intensity
                    }
                    val patch = com.rainalarm.app.domain.RegionalPointPatch(
                        area.rasterWidth, area.rasterHeight, rect.left, rect.top,
                        bitmap.width, bitmap.height, pixel.x, pixel.y, values,
                    )
                    val atPlace = patch.sample()
                    PointIntensity(minOf(minimum, atPlace), atPlace, maxOf(maximum, atPlace), patch)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                decoder.recycle()
            }
        } catch (oom: OutOfMemoryError) {
            throw java.io.IOException("Not enough memory to sample regional radar", oom)
        }
    }

    @Suppress("DEPRECATION")
    private fun sampleSelectedVelocity(
        bytes: ByteArray,
        area: RegionalRadarArea,
        place: SavedPlace,
    ): Pair<Float, Float>? {
        val pixel = com.rainalarm.app.domain.RegionalProjection.pixelFor(
            area, place.latitude, place.longitude,
        ) ?: return null
        val centerX = kotlin.math.floor(pixel.x).toInt().coerceIn(0, area.rasterWidth - 1)
        val centerY = kotlin.math.floor(pixel.y).toInt().coerceIn(0, area.rasterHeight - 1)
        val rect = Rect(
            (centerX - 1).coerceAtLeast(0),
            (centerY - 1).coerceAtLeast(0),
            (centerX + 2).coerceAtMost(area.rasterWidth),
            (centerY + 2).coerceAtMost(area.rasterHeight),
        )
        return try {
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            try {
                val bitmap = decoder.decodeRegion(
                    rect,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: return null
                try {
                    require(bitmap.width == rect.width() && bitmap.height == rect.height())
                    val red = FloatArray(bitmap.width * bitmap.height)
                    val green = FloatArray(red.size)
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val color = bitmap[x, y]
                        red[y * bitmap.width + x] = ((color shr 16) and 0xff) / 255f
                        green[y * bitmap.width + x] = ((color shr 8) and 0xff) / 255f
                    }
                    val localX = pixel.x * (area.rasterWidth - 1) / area.rasterWidth - rect.left
                    val localY = pixel.y * (area.rasterHeight - 1) / area.rasterHeight - rect.top
                    val r = com.rainalarm.app.domain.RadarLinearSampling.bilinear(
                        red, bitmap.width, bitmap.height, localX, localY,
                    )
                    val g = com.rainalarm.app.domain.RadarLinearSampling.bilinear(
                        green, bitmap.width, bitmap.height, localX, localY,
                    )
                    VelocityTextureCodec.decodeNormalized(r, g).let { vector ->
                        vector.first * area.velocityScale to vector.second * area.velocityScale
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                decoder.recycle()
            }
        } catch (oom: OutOfMemoryError) {
            throw java.io.IOException("Not enough memory to sample regional velocity", oom)
        }
    }

    private fun accountBytes(total: AtomicLong, added: Int) {
        require(added in 1..MAX_FILE_BYTES)
        require(total.addAndGet(added.toLong()) <= MAX_SESSION_BYTES) {
            "Regional radar session exceeds the compressed cache limit"
        }
    }

    private companion object {
        const val MAX_DOWNLOAD_CONCURRENCY = 2
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        const val MAX_SESSION_BYTES = 64L * 1024 * 1024
    }
}

@SuppressLint("LogNotTimber") // Provider/fallback selection is intentionally logged without a logging dependency.
class RadarProviderCoordinator(
    private val settings: RadarSettingsRepository,
    private val regional: MeteoGroupRadarSessionLoader = MeteoGroupRadarSessionLoader(),
    private val open: RadarSessionLoader = RadarSessionLoader(),
) {
    suspend fun load(
        place: SavedPlace,
        maxFrames: Int? = null,
        mode: RadarLoadMode = RadarLoadMode.SCREEN_TWO_TIER,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): RadarSession {
        val startedNanos = System.nanoTime()
        val totalBudget = RadarLoadDeadline.total(mode)
        suspend fun loadOpen(message: String? = null, showLikelySnow: Boolean = false): RadarSession {
            val remaining = RadarLoadDeadline.remaining(totalBudget, startedNanos, System.nanoTime())
            if (remaining == 0L) throw RadarLoadTimedOutException("Radar download timed out. Tap refresh to try again.")
            val loaded = try {
                withTimeout(remaining) {
                    open.load(place, maxFrames, mode, onProgress, showLikelySnow)
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                throw RadarLoadTimedOutException("Radar download timed out. Tap refresh to try again.")
            }
            return loaded.copy(providerSelection = RadarProviderSelection(
                if (message == null) RadarProviderKind.OPEN_RAINVIEWER else RadarProviderKind.METEOGROUP_REGIONAL,
                RadarProviderKind.OPEN_RAINVIEWER,
                message,
            ))
        }
        return when (settings.selectedProvider()) {
            RadarProviderKind.OPEN_RAINVIEWER -> {
                Log.i("RainRadarProvider", "Loading requested open radar session")
                loadOpen(showLikelySnow = RainViewerSnowPolicy.effective(
                    RadarProviderKind.OPEN_RAINVIEWER,
                    settings.selectedShowLikelySnow(),
                ))
            }
            RadarProviderKind.METEOGROUP_REGIONAL -> {
                val area = RegionalRadarAreas.forPoint(place.latitude, place.longitude)
                if (area == null) {
                    Log.i("RainRadarProvider", "No regional area matched; selecting open fallback")
                    loadOpen("MeteoGroup regional coverage is unavailable here; using open radar.")
                } else {
                    Log.i("RainRadarProvider", "Loading requested MeteoGroup area=${area.id}")
                    when (val attempt = RadarLoadDeadline.attemptPrimary(RadarLoadDeadline.primary(mode)) {
                        regional.load(place, maxFrames, mode, onProgress)
                    }) {
                        is PrimaryRadarAttempt.Ready -> attempt.value
                        PrimaryRadarAttempt.TimedOut -> {
                            Log.w("RainRadarProvider", "MeteoGroup session exceeded primary deadline; selecting open fallback")
                            loadOpen("MeteoGroup regional radar timed out; using open radar for this session.")
                        }
                        is PrimaryRadarAttempt.Failed -> {
                            Log.w("RainRadarProvider", "MeteoGroup load failed; selecting open fallback", attempt.cause)
                            loadOpen("MeteoGroup regional radar could not load; using open radar for this session.")
                        }
                    }
                }
            }
        }
    }
}

object VelocityTextureCodec {
    fun decode(red: Int, green: Int): Pair<Float, Float> =
        decodeNormalized(red.coerceIn(0, 255) / 255f, green.coerceIn(0, 255) / 255f)

    fun decodeNormalized(red: Float, green: Float): Pair<Float, Float> =
        (red.coerceIn(0f, 1f) * 2f - 1f) to (green.coerceIn(0f, 1f) * 2f - 1f)
}
