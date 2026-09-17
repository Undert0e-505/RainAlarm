package com.rainalarm.app.data

import android.content.Context
import androidx.core.content.edit
import com.rainalarm.app.domain.PrecipitationSlot
import com.rainalarm.app.domain.RainAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val OPEN_METEO_BASE_URL = "https://api.open-meteo.com/v1/forecast"

fun buildForecastUrl(place: SavedPlace, baseUrl: String = OPEN_METEO_BASE_URL): String {
    val endpoint = URL(baseUrl)
    require(endpoint.protocol == "https") { "Forecast endpoint must use HTTPS" }
    require(endpoint.host.isNotBlank()) { "Forecast endpoint host is required" }
    val parameters = linkedMapOf(
        "latitude" to place.latitude.toString(),
        "longitude" to place.longitude.toString(),
        "minutely_15" to "precipitation,rain,showers,snowfall",
        "hourly" to "precipitation_probability",
        "current" to "temperature_2m,weather_code,is_day",
        "forecast_days" to "1",
        "timezone" to "auto",
    )
    val query = parameters.entries.joinToString("&") { (key, value) ->
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        "${key}=${encoded}"
    }
    return "${baseUrl}?${query}"
}

interface ForecastEndpoint {
    suspend fun fetch(place: SavedPlace): String
}

class OpenMeteoEndpoint(
    private val baseUrl: String = OPEN_METEO_BASE_URL,
) : ForecastEndpoint {
    override suspend fun fetch(place: SavedPlace): String = withContext(Dispatchers.IO) {
        val connection = URL(buildForecastUrl(place, baseUrl)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RainAlarm/0.1")
            check(connection.responseCode in 200..299) { "Forecast service returned ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
data class OpenMeteoResponse(
    val timezone: String = "UTC",
    @SerialName("minutely_15") val minutely15: Minutely15? = null,
    val hourly: Hourly? = null,
)

@Serializable
data class Minutely15(
    val time: List<String> = emptyList(),
    val precipitation: List<Double> = emptyList(),
)

@Serializable
data class Hourly(
    val time: List<String> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
)

object OpenMeteoMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(
        body: String,
        fetchedAt: Instant,
        now: Instant = Instant.now(),
        cached: Boolean = false,
        place: SavedPlace = DEFAULT_PLACE,
    ): ForecastSnapshot {
        val response = json.decodeFromString<OpenMeteoResponse>(body)
        val zone = runCatching { ZoneId.of(response.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val hourlyProbability = response.hourly?.time.orEmpty()
            .zip(response.hourly?.precipitationProbability.orEmpty())
            .associate { (time, chance) ->
                LocalDateTime.parse(time).atZone(zone).toInstant().truncatedTo(ChronoUnit.HOURS) to chance
            }
        val slots = response.minutely15?.time.orEmpty()
            .zip(response.minutely15?.precipitation.orEmpty())
            .map { (time, precipitation) ->
                val instant = LocalDateTime.parse(time).atZone(zone).toInstant()
                PrecipitationSlot(
                    startsAt = instant,
                    precipitationMm = precipitation,
                    probabilityPercent = hourlyProbability[instant.truncatedTo(ChronoUnit.HOURS)],
                )
            }
            .filter { !it.startsAt.isBefore(now.minusSeconds(15 * 60L)) }
            .take(9)
        return ForecastSnapshot(
            locationName = place.name,
            fetchedAt = fetchedAt,
            slots = slots,
            summary = RainAnalyzer.analyze(slots, now, fetchedAt),
            isDemo = false,
            sourceLabel = "Open-Meteo",
            isCached = cached,
        )
    }
}

class OpenMeteoForecastRepository(
    context: Context,
    private val endpoint: ForecastEndpoint = OpenMeteoEndpoint(),
    private val demo: ForecastRepository = DemoForecastRepository(),
) : ForecastRepository {
    private val preferences = context.getSharedPreferences("forecast_cache", Context.MODE_PRIVATE)
    private val places = PlacePreferences(context)

    override suspend fun forecast(now: Instant): ForecastSnapshot {
        val place = places.selected.first()
        require(!place.isCurrentLocation) { "A live device fix is required for current location" }
        return forecastFor(place, now)
    }

    suspend fun forecastFor(place: SavedPlace, now: Instant = Instant.now()): ForecastSnapshot {
        return runCatching {
            val body = endpoint.fetch(place)
            preferences.edit {
                putString("body", body)
                putLong("fetched_at", now.epochSecond)
                putString("place_name", place.name)
                putString("place_key", "${place.latitude},${place.longitude}")
            }
            OpenMeteoMapper.fromJson(body, fetchedAt = now, now = now, place = place)
        }.getOrElse {
            val cachedBody = preferences.getString("body", null)
            val cachedAt = preferences.getLong("fetched_at", 0L)
            val cachedKey = preferences.getString("place_key", null)
            val currentKey = "${place.latitude},${place.longitude}"
            if (cachedBody != null && cachedAt > 0L && cachedKey == currentKey) {
                runCatching {
                    OpenMeteoMapper.fromJson(
                        cachedBody,
                        fetchedAt = Instant.ofEpochSecond(cachedAt),
                        now = now,
                        cached = true,
                        place = place,
                    )
                }.getOrElse { demo.forecast(now) }
            } else {
                demo.forecast(now)
            }
        }
    }
}
