@file:android.annotation.SuppressLint("LogNotTimber")
package com.rainalarm.app.data

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Open-Meteo current values are 15-minute model data, not station observations. */
data class CurrentWeather(
    val latitude: Double,
    val longitude: Double,
    val validEpochSeconds: Long,
    val fetchedEpochSeconds: Long,
    val temperatureC: Double?,
    val pressureHpa: Double?,
    val humidityPercent: Int?,
    val uvIndex: Double?,
    val isDay: Boolean?,
    val windSpeedKmh: Double?,
    val windFromDegrees: Double?,
    val timeZone: String? = null,
    val sunriseEpochSeconds: Long? = null,
    val sunsetEpochSeconds: Long? = null,
) {
    fun freshAt(now: Long): Boolean = now - validEpochSeconds in -900L..3_600L
    fun daylightFreshAt(now: Long): Boolean = now - validEpochSeconds in -900L..1_800L
    fun solarDate(): LocalDate? = timeZone?.let { zone ->
        sunriseEpochSeconds?.let { Instant.ofEpochSecond(it).atZone(ZoneId.of(zone)).toLocalDate() }
    }
    fun solarToday(now: Long): Pair<String, String>? {
        val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        if (solarDate() != Instant.ofEpochSecond(now).atZone(zone).toLocalDate()) return null
        val format = DateTimeFormatter.ofPattern("HH:mm")
        return Pair(
            sunriseEpochSeconds?.let { Instant.ofEpochSecond(it).atZone(zone).format(format) } ?: "—",
            sunsetEpochSeconds?.let { Instant.ofEpochSecond(it).atZone(zone).format(format) } ?: "—",
        )
    }
}

/** The visible camera bounds, rather than the selected point, define the wind sampling footprint. */
data class WindViewport(val south: Double, val west: Double, val north: Double, val east: Double) {
    init { require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite()) }
    val latitudeSpan: Double get() = (north - south).coerceIn(0.0000001, 170.0)
    val longitudeSpan: Double get() = (east - west).let { raw ->
        when {
            abs(raw) < 0.0000001 -> 0.0000001
            abs(raw) >= 360.0 -> 360.0
            else -> ((raw + 360.0) % 360.0).coerceAtLeast(0.0000001)
        }
    }

    /** Quantization avoids refetching on every tiny settled camera movement. */
    fun requestKey(): String {
        fun scale(span: Double): Int = (ln(span) / ln(1.25)).roundToInt()
        val latScale = scale(latitudeSpan)
        val lonScale = scale(longitudeSpan)
        val latStep = 1.25.pow(latScale) / 8.0
        val lonStep = 1.25.pow(lonScale) / 8.0
        val centerLat = (south + north) / 2.0
        val centerLon = wrapLongitude(west + longitudeSpan / 2.0)
        return "$latScale:$lonScale:${floor(centerLat / latStep).toLong()}:${floor(centerLon / lonStep).toLong()}"
    }

    fun coordinates(): List<Pair<Double, Double>> {
        return WindGridSamplingPolicy.viewportFractions.flatMap { latitudeFraction ->
            WindGridSamplingPolicy.viewportFractions.map { longitudeFraction ->
                (south + latitudeSpan * latitudeFraction).coerceIn(-85.0, 85.0) to
                    wrapLongitude(west + longitudeSpan * longitudeFraction)
            }
        }
    }

    companion object {
        fun wrapLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}

/**
 * Five samples per axis keep one offscreen coverage row/column on every side while the visible
 * arrows settle at the quarter, midpoint, and three-quarter positions of the current viewport.
 */
object WindGridSamplingPolicy {
    private const val OFFSCREEN_MARGIN_FRACTION = 0.17
    val viewportFractions: List<Double> = listOf(
        -OFFSCREEN_MARGIN_FRACTION,
        0.25,
        0.5,
        0.75,
        1.17,
    )
}

data class WindGrid(
    val viewportKey: String,
    val points: List<CurrentWeather>,
    val requestedPositions: List<Pair<Double, Double>>,
    val fetchedEpochSeconds: Long,
    val timelineFrames: List<WindGridFrame> = listOf(
        WindGridFrame(points.firstOrNull()?.validEpochSeconds ?: fetchedEpochSeconds, points),
    ),
) {
    init {
        require(points.size == requestedPositions.size)
        require(timelineFrames.isNotEmpty())
        require(timelineFrames.zipWithNext().all { (first, second) ->
            second.validEpochSeconds > first.validEpochSeconds
        })
        require(timelineFrames.all { it.points.size == requestedPositions.size })
    }
    // The model may report several requests at the same coarse cell centre. Keep those
    // source coordinates in each CurrentWeather, but draw at the distinct request sites.
    fun renderCoordinates(): List<Pair<Double, Double>> = requestedPositions

    fun displayedAt(cursorEpochSeconds: Long): WindGrid {
        val frame = WindTimelinePolicy.frameAtOrBefore(timelineFrames, cursorEpochSeconds)
        return if (points === frame.points) this else copy(points = frame.points)
    }
}

data class WindGridFrame(val validEpochSeconds: Long, val points: List<CurrentWeather>)

data class WindTimelineWindow(val startEpochSeconds: Long, val endEpochSeconds: Long) {
    init { require(startEpochSeconds <= endEpochSeconds && endEpochSeconds - startEpochSeconds <= 8 * 3_600L) }
    val cacheKey: String get() = "$startEpochSeconds:$endEpochSeconds"
}

object WindTimelinePolicy {
    const val cadenceSeconds = 15 * 60L
    private const val maxWindowSeconds = 8 * 3_600L

    fun requestWindow(startEpochSeconds: Long, endEpochSeconds: Long): WindTimelineWindow {
        require(startEpochSeconds <= endEpochSeconds)
        val start = Math.floorDiv(startEpochSeconds, cadenceSeconds) * cadenceSeconds
        val endFloor = Math.floorDiv(endEpochSeconds, cadenceSeconds) * cadenceSeconds
        val endCeiling = if (endFloor == endEpochSeconds) endFloor else endFloor + cadenceSeconds
        // Radar windows are normally only a few hours. Keep a corrupt or unexpectedly long
        // provider timeline from expanding the 25-coordinate model request without bound.
        val end = endCeiling.coerceAtMost(start + maxWindowSeconds)
        return WindTimelineWindow(start, end)
    }

    fun frameAtOrBefore(frames: List<WindGridFrame>, cursorEpochSeconds: Long): WindGridFrame {
        require(frames.isNotEmpty())
        return frames.lastOrNull { it.validEpochSeconds <= cursorEpochSeconds }
            ?: frames.first()
    }
}

/**
 * The 25-coordinate wind timeline is materially larger than point weather. Give weak mobile
 * connections enough time to complete, while retaining a hard overall deadline and bounded retries
 * for genuinely transient failures. The first retry is deliberately quick so a brief overload or
 * connection reset does not impose a 15-second minimum delay. The caller remains in its loading
 * state for this whole policy.
 */
internal object WindGridNetworkPolicy {
    const val connectTimeoutMillis = 15_000
    const val readTimeoutMillis = 30_000
    const val totalTimeoutMillis = 55_000L
    const val minimumRetrySpacingMillis = 1_500L
    val attemptOffsetsMillis: List<Long> = listOf(0L, 1_500L, 10_000L, 35_000L)
    val maximumAttempts: Int get() = attemptOffsetsMillis.size

    suspend fun <T : Any> execute(
        request: suspend (attempt: Int) -> T,
        pause: suspend (Long) -> Unit = { delay(it) },
        elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        onAttempt: (WindGridAttemptEvent) -> Unit = {},
    ): T {
        val startedAt = elapsedRealtimeMillis()
        val result = withTimeoutOrNull(totalTimeoutMillis) {
            var lastTransientFailure: Exception? = null
            var retryNotBeforeOffsetMillis = 0L
            attemptOffsetsMillis.forEachIndexed { index, scheduledOffset ->
                if (index > 0) {
                    val elapsed = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L)
                    pause(maxOf(
                        scheduledOffset - elapsed,
                        retryNotBeforeOffsetMillis - elapsed,
                        minimumRetrySpacingMillis,
                    ))
                }
                val attemptStartedAt = elapsedRealtimeMillis()
                val attemptNumber = index + 1
                onAttempt(WindGridAttemptEvent.Started(
                    attemptNumber,
                    (attemptStartedAt - startedAt).coerceAtLeast(0L),
                ))
                try {
                    val value = request(attemptNumber)
                    onAttempt(WindGridAttemptEvent.Succeeded(
                        attemptNumber,
                        (elapsedRealtimeMillis() - attemptStartedAt).coerceAtLeast(0L),
                    ))
                    return@withTimeoutOrNull value
                } catch (cancelled: CancellationException) {
                    onAttempt(WindGridAttemptEvent.Failed(
                        attemptNumber,
                        (elapsedRealtimeMillis() - attemptStartedAt).coerceAtLeast(0L),
                        WindGridFailureDiagnostics.classify(cancelled),
                    ))
                    throw cancelled
                } catch (failure: Exception) {
                    val diagnostic = WindGridFailureDiagnostics.classify(failure)
                    onAttempt(WindGridAttemptEvent.Failed(
                        attemptNumber,
                        (elapsedRealtimeMillis() - attemptStartedAt).coerceAtLeast(0L),
                        diagnostic,
                    ))
                    if (!isTransient(failure)) throw failure
                    lastTransientFailure = failure
                    val elapsed = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L)
                    retryNotBeforeOffsetMillis = elapsed +
                        ((failure as? WindGridHttpException)?.retryAfterMillis ?: 0L)
                }
            }
            throw WindGridAttemptsExhaustedException(requireNotNull(lastTransientFailure))
        }
        return result ?: throw WindGridRequestTimeoutException()
    }

    fun isTransient(failure: Throwable): Boolean = when (failure) {
        is WindGridHttpException -> failure.statusCode == 408 || failure.statusCode == 425 ||
            failure.statusCode == 429 || failure.statusCode in 500..599
        is IOException -> true
        else -> false
    }

    /** Supports the common delta-seconds Retry-After form without extending the bounded window. */
    fun retryAfterMillis(value: String?): Long? = value?.trim()?.toLongOrNull()
        ?.takeIf { it >= 0L }
        ?.let { seconds ->
            if (seconds > totalTimeoutMillis / 1_000L) totalTimeoutMillis else seconds * 1_000L
        }
}

internal sealed interface WindGridAttemptEvent {
    val attempt: Int

    data class Started(
        override val attempt: Int,
        val offsetMillis: Long,
    ) : WindGridAttemptEvent

    data class Succeeded(
        override val attempt: Int,
        val durationMillis: Long,
    ) : WindGridAttemptEvent

    data class Failed(
        override val attempt: Int,
        val durationMillis: Long,
        val diagnostic: WindGridFailureDiagnostic,
    ) : WindGridAttemptEvent
}

internal class WindGridHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
) :
    IOException("Wind grid request returned HTTP $statusCode")

internal class WindGridAttemptsExhaustedException(lastFailure: Exception) :
    IOException("Wind grid recovery attempts exhausted", lastFailure)

internal class WindGridRequestTimeoutException : IOException("Wind grid request timed out")

internal data class WindGridFailureDiagnostic(
    val category: String,
    val httpStatus: Int? = null,
    val retriesExhausted: Boolean = false,
)

internal object WindGridFailureDiagnostics {
    fun classify(failure: Throwable): WindGridFailureDiagnostic = when (failure) {
        is WindGridAttemptsExhaustedException -> classify(requireNotNull(failure.cause)).copy(
            retriesExhausted = true,
        )
        is WindGridHttpException -> WindGridFailureDiagnostic("http", failure.statusCode)
        is WindGridRequestTimeoutException -> WindGridFailureDiagnostic("timeout")
        is CancellationException -> WindGridFailureDiagnostic("cancellation")
        is kotlinx.serialization.SerializationException,
        is IllegalArgumentException,
        is NoSuchElementException -> WindGridFailureDiagnostic("parse_validation")
        is IOException -> WindGridFailureDiagnostic("network_io")
        else -> WindGridFailureDiagnostic("unexpected")
    }

    fun logFields(failure: Throwable): String = classify(failure).let { diagnostic ->
        buildString {
            append("category=").append(diagnostic.category)
            diagnostic.httpStatus?.let { append(" httpStatus=").append(it) }
            append(" retriesExhausted=").append(diagnostic.retriesExhausted)
        }
    }
}

/** Cache-aside sequencing keeps slow/cancelled HTTP work outside the short cache mutex sections. */
internal object WindGridCachePolicy {
    suspend fun <T : Any> load(
        read: suspend () -> T?,
        acquire: suspend () -> T,
        write: suspend (T) -> Unit,
    ): T {
        read()?.let { return it }
        val value = acquire()
        currentCoroutineContext().ensureActive()
        write(value)
        return value
    }
}

object OpenMeteoWindTimelineCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun url(coordinates: List<Pair<Double, Double>>, window: WindTimelineWindow): String {
        require(coordinates.isNotEmpty() && coordinates.size <= 25)
        require(coordinates.all { it.first in -85.0..85.0 && it.second in -180.0..180.0 })
        val latitudes = coordinates.joinToString(",") { it.first.toString() }
        val longitudes = coordinates.joinToString(",") { it.second.toString() }
        fun timestamp(value: Long): String = Instant.ofEpochSecond(value).atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        return "https://api.open-meteo.com/v1/forecast?latitude=$latitudes&longitude=$longitudes" +
            "&minutely_15=wind_speed_10m,wind_direction_10m" +
            "&start_minutely_15=${timestamp(window.startEpochSeconds)}" +
            "&end_minutely_15=${timestamp(window.endEpochSeconds)}" +
            "&timezone=UTC&timeformat=unixtime"
    }

    fun parse(body: String, expected: Int, fetchedAt: Long): List<WindGridFrame> {
        require(body.length <= 256 * 1024)
        val root = json.parseToJsonElement(body)
        val entries = if (expected == 1) listOf(root.jsonObject) else root.jsonArray.map { it.jsonObject }
        require(entries.size == expected)
        var sharedTimes: List<Long>? = null
        val pointsByCoordinate = entries.map { entry ->
            val units = entry.getValue("minutely_15_units").jsonObject
            require(units["time"]?.jsonPrimitive?.content == "unixtime")
            require(units["wind_speed_10m"]?.jsonPrimitive?.content == "km/h")
            require(units["wind_direction_10m"]?.jsonPrimitive?.content == "°")
            val values = entry.getValue("minutely_15").jsonObject
            val times = values.getValue("time").jsonArray.map {
                it.jsonPrimitive.content.toLong()
            }
            require(times.isNotEmpty() && times.size <= 33)
            require(times.zipWithNext().all { (first, second) ->
                second - first == WindTimelinePolicy.cadenceSeconds
            })
            if (sharedTimes == null) sharedTimes = times else require(sharedTimes == times)
            val speeds = values.getValue("wind_speed_10m").jsonArray
            val directions = values.getValue("wind_direction_10m").jsonArray
            require(speeds.size == times.size && directions.size == times.size)
            val latitude = entry.getValue("latitude").jsonPrimitive.content.toDouble()
            val longitude = entry.getValue("longitude").jsonPrimitive.content.toDouble()
            require(latitude.isFinite() && latitude in -85.0..85.0)
            require(longitude.isFinite() && longitude in -180.0..180.0)
            times.indices.map { index ->
                val speed = speeds[index].jsonPrimitive.content.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..400.0 }
                val direction = directions[index].jsonPrimitive.content.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..360.0 }
                CurrentWeather(latitude, longitude, times[index], fetchedAt,
                    null, null, null, null, null, speed, direction)
            }
        }
        val times = requireNotNull(sharedTimes)
        return times.indices.map { timeIndex ->
            WindGridFrame(times[timeIndex], pointsByCoordinate.map { it[timeIndex] })
        }
    }
}

object OpenMeteoCurrentCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val fields = "temperature_2m,relative_humidity_2m,pressure_msl,uv_index,is_day,wind_speed_10m,wind_direction_10m"

    fun url(coordinates: List<Pair<Double, Double>>, includeSolar: Boolean = false): String {
        require(coordinates.isNotEmpty() && coordinates.size <= 25)
        require(coordinates.all { it.first in -85.0..85.0 && it.second in -180.0..180.0 })
        val latitudes = coordinates.joinToString(",") { it.first.toString() }
        val longitudes = coordinates.joinToString(",") { it.second.toString() }
        return "https://api.open-meteo.com/v1/forecast?latitude=$latitudes&longitude=$longitudes" +
            "&current=$fields&timezone=${if (includeSolar) "auto" else "UTC"}&timeformat=unixtime" +
            if (includeSolar) "&daily=sunrise,sunset&forecast_days=2" else ""
    }

    fun parse(body: String, expected: Int, fetchedAt: Long): List<CurrentWeather> {
        require(body.length <= 256 * 1024)
        val root = json.parseToJsonElement(body)
        val entries = if (expected == 1) listOf(root.jsonObject) else root.jsonArray.map { it.jsonObject }
        require(entries.size == expected)
        return entries.map { entry ->
            val units = entry.getValue("current_units").jsonObject
            require(units["temperature_2m"]?.jsonPrimitive?.content == "°C")
            require(units["pressure_msl"]?.jsonPrimitive?.content == "hPa")
            require(units["relative_humidity_2m"]?.jsonPrimitive?.content == "%")
            require(units["uv_index"]?.jsonPrimitive?.content == "")
            require(units["wind_speed_10m"]?.jsonPrimitive?.content == "km/h")
            require(units["wind_direction_10m"]?.jsonPrimitive?.content == "°")
            val current = entry.getValue("current").jsonObject
            val time = current.getValue("time").jsonPrimitive.content.toLong()
            val temperature = current.number("temperature_2m")?.takeIf { it in -100.0..70.0 }
            val pressure = current.number("pressure_msl")?.takeIf { it in 800.0..1_200.0 }
            val humidity = current.number("relative_humidity_2m")?.roundToInt()?.takeIf { it in 0..100 }
            val uv = current.number("uv_index")?.takeIf { it in 0.0..30.0 }
            val speed = current.number("wind_speed_10m")?.takeIf { it in 0.0..400.0 }
            val direction = current.number("wind_direction_10m")?.takeIf { it in 0.0..360.0 }
            val zone = entry["timezone"]?.jsonPrimitive?.content?.takeIf {
                runCatching { ZoneId.of(it) }.isSuccess
            }
            val daily = entry["daily"] as? JsonObject
            val sunrise = (daily?.get("sunrise") as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content?.toLongOrNull()
            }.orEmpty()
            val sunset = (daily?.get("sunset") as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content?.toLongOrNull()
            }.orEmpty()
            val localDate = zone?.let { Instant.ofEpochSecond(fetchedAt).atZone(ZoneId.of(it)).toLocalDate() }
            val solarIndex = if (localDate != null) sunrise.indexOfFirst {
                Instant.ofEpochSecond(it).atZone(ZoneId.of(zone)).toLocalDate() == localDate
            } else -1
            CurrentWeather(
                entry.getValue("latitude").jsonPrimitive.content.toDouble(),
                entry.getValue("longitude").jsonPrimitive.content.toDouble(),
                time, fetchedAt, temperature, pressure, humidity, uv,
                when (current["is_day"]?.jsonPrimitive?.content) {
                    "0" -> false
                    "1" -> true
                    else -> null
                }, speed, direction, zone,
                sunrise.getOrNull(solarIndex), sunset.getOrNull(solarIndex),
            )
        }
    }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf(Double::isFinite)

}

/** Process-scoped, bounded, place-aware cache; no polling and no persisted coordinates. */
object WeatherLayerRepository {
    private var pointCache: Pair<String, CurrentWeather>? = null
    private val windCache = LinkedHashMap<String, WindGrid>(8, 0.75f, true)
    private val pointMutex = Mutex()
    private val windMutex = Mutex()
    private fun key(place: SavedPlace) = "${place.latitude.toBits()}:${place.longitude.toBits()}"

    suspend fun point(place: SavedPlace, force: Boolean = false): CurrentWeather = pointMutex.withLock {
        val now = Instant.now().epochSecond
        pointCache?.let { (cachedKey, value) ->
            if (!force && cachedKey == key(place) && now - value.fetchedEpochSeconds in 0..900 &&
                value.freshAt(now) && (value.solarDate() == null || value.solarToday(now) != null))
                return@withLock value
        }
        val value = OpenMeteoCurrentCodec.parse(fetch(OpenMeteoCurrentCodec.url(
            listOf(place.latitude to place.longitude), includeSolar = true), 64 * 1024), 1, now).single()
        require(value.freshAt(Instant.now().epochSecond)) { "Model weather is stale" }
        pointCache = key(place) to value
        value
    }

    suspend fun wind(
        viewport: WindViewport,
        window: WindTimelineWindow,
        force: Boolean = false,
    ): WindGrid {
        val requestStartedAt = System.nanoTime() / 1_000_000L
        val result = withTimeoutOrNull(WindGridNetworkPolicy.totalTimeoutMillis) {
            val viewportKey = viewport.requestKey()
            val key = "$viewportKey:${window.cacheKey}"
            WindGridCachePolicy.load(
                read = {
                    val waitStartedAt = System.nanoTime() / 1_000_000L
                    val cached = windMutex.withLock {
                        val now = Instant.now().epochSecond
                        windCache[key]?.takeIf {
                            !force && now - it.fetchedEpochSeconds in 0..900
                        }
                    }
                    Log.d(
                        "RainRadarWind",
                        "cache check viewportKey=$viewportKey force=$force " +
                            "waitMs=${(System.nanoTime() / 1_000_000L - waitStartedAt).coerceAtLeast(0L)} " +
                            "hit=${cached != null}",
                    )
                    cached
                },
                acquire = {
                    val now = Instant.now().epochSecond
                    val coordinates = viewport.coordinates()
                    val requestUrl = OpenMeteoWindTimelineCodec.url(coordinates, window)
                    val response = WindGridNetworkPolicy.execute(
                        request = { _ ->
                            fetch(
                                requestUrl,
                                256 * 1024,
                                connectTimeoutMillis = WindGridNetworkPolicy.connectTimeoutMillis,
                                readTimeoutMillis = WindGridNetworkPolicy.readTimeoutMillis,
                                retryableHttpStatus = true,
                            )
                        },
                        onAttempt = { event ->
                            val message = when (event) {
                                is WindGridAttemptEvent.Started ->
                                    "attempt=${event.attempt} start offsetMs=${event.offsetMillis}"
                                is WindGridAttemptEvent.Succeeded ->
                                    "attempt=${event.attempt} success durationMs=${event.durationMillis}"
                                is WindGridAttemptEvent.Failed -> buildString {
                                    append("attempt=").append(event.attempt)
                                    append(" result=").append(event.diagnostic.category)
                                    event.diagnostic.httpStatus?.let {
                                        append(" httpStatus=").append(it)
                                    }
                                    append(" durationMs=").append(event.durationMillis)
                                }
                            }
                            Log.d("RainRadarWind", "viewportKey=$viewportKey $message")
                        },
                    )
                    val parseStartedAt = System.nanoTime() / 1_000_000L
                    val frames = OpenMeteoWindTimelineCodec.parse(response, 25, now)
                    Log.d(
                        "RainRadarWind",
                        "parse complete viewportKey=$viewportKey frames=${frames.size} " +
                            "durationMs=${(System.nanoTime() / 1_000_000L - parseStartedAt).coerceAtLeast(0L)}",
                    )
                    val displayed = WindTimelinePolicy.frameAtOrBefore(frames, now)
                    WindGrid(viewportKey, displayed.points, coordinates, now, frames)
                },
                write = { grid ->
                    val waitStartedAt = System.nanoTime() / 1_000_000L
                    windMutex.withLock {
                        windCache[key] = grid
                        while (windCache.size > 8) windCache.remove(windCache.keys.first())
                    }
                    Log.d(
                        "RainRadarWind",
                        "cache store viewportKey=$viewportKey " +
                            "waitMs=${(System.nanoTime() / 1_000_000L - waitStartedAt).coerceAtLeast(0L)} " +
                            "totalMs=${(System.nanoTime() / 1_000_000L - requestStartedAt).coerceAtLeast(0L)}",
                    )
                },
            )
        }
        return result ?: throw WindGridRequestTimeoutException()
    }

    suspend fun wind(viewport: WindViewport, force: Boolean = false): WindGrid {
        val now = Instant.now().epochSecond
        return wind(viewport, WindTimelinePolicy.requestWindow(now - 3 * 3_600L, now + 3_600L), force)
    }

    private suspend fun fetch(
        rawUrl: String,
        maxBytes: Int,
        connectTimeoutMillis: Int = 5_000,
        readTimeoutMillis: Int = 8_000,
        retryableHttpStatus: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val url = URL(rawUrl)
        require(url.protocol == "https" && url.host == "api.open-meteo.com")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        try {
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                if (retryableHttpStatus) throw WindGridHttpException(
                    responseCode,
                    WindGridNetworkPolicy.retryAfterMillis(connection.getHeaderField("Retry-After")),
                )
                throw IOException("Weather request returned HTTP $responseCode")
            }
            require(connection.contentType?.startsWith("application/json") == true)
            require(connection.contentLengthLong in -1..maxBytes.toLong())
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(out.size() + read <= maxBytes)
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }
}
