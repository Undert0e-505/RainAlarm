package com.rainalarm.app.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
        val latMargin = latitudeSpan * 0.17
        val lonMargin = longitudeSpan * 0.17
        return (0..4).flatMap { row -> (0..4).map { column ->
            (south - latMargin + (latitudeSpan + 2 * latMargin) * row / 4.0).coerceIn(-85.0, 85.0) to
                wrapLongitude(west - lonMargin + (longitudeSpan + 2 * lonMargin) * column / 4.0)
        } }
    }

    companion object {
        fun wrapLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}

data class WindGrid(
    val viewportKey: String,
    val points: List<CurrentWeather>,
    val requestedPositions: List<Pair<Double, Double>>,
    val fetchedEpochSeconds: Long,
) {
    init { require(points.size == requestedPositions.size) }
    // The model may report several requests at the same coarse cell centre. Keep those
    // source coordinates in each CurrentWeather, but draw at the distinct request sites.
    fun renderCoordinates(): List<Pair<Double, Double>> = requestedPositions
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

    suspend fun wind(viewport: WindViewport, force: Boolean = false): WindGrid = windMutex.withLock {
        val now = Instant.now().epochSecond
        val key = viewport.requestKey()
        windCache[key]?.let { grid ->
            if (!force && now - grid.fetchedEpochSeconds in 0..900 && grid.points.all { it.freshAt(now) })
                return@withLock grid
        }
        val coordinates = viewport.coordinates()
        val grid = WindGrid(key, OpenMeteoCurrentCodec.parse(fetch(OpenMeteoCurrentCodec.url(coordinates), 256 * 1024), 25, now),
            coordinates, now)
        require(grid.points.all { it.freshAt(Instant.now().epochSecond) }) { "Wind model is stale" }
        windCache[key] = grid
        while (windCache.size > 8) windCache.remove(windCache.keys.first())
        grid
    }

    private suspend fun fetch(rawUrl: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val url = URL(rawUrl)
        require(url.protocol == "https" && url.host == "api.open-meteo.com")
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        try {
            require(connection.responseCode == 200)
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
