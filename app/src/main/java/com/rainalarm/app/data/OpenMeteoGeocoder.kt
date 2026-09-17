package com.rainalarm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com/v1/search"

data class PlaceSearchResult(
    val name: String,
    val detail: String,
    val latitude: Double,
    val longitude: Double,
) {
    fun toSavedPlace(): SavedPlace = SavedPlace(
        name = name,
        latitude = latitude,
        longitude = longitude,
    )
}

fun buildGeocodingUrl(
    query: String,
    language: String,
    baseUrl: String = GEOCODING_BASE_URL,
): String {
    val trimmed = query.trim()
    require(trimmed.length >= 2) { "Search needs at least two characters" }
    val endpoint = URL(baseUrl)
    require(endpoint.protocol == "https" && endpoint.host.isNotBlank()) {
        "Geocoding endpoint must use HTTPS"
    }
    fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    return "${baseUrl}?name=${encode(trimmed)}&count=8&language=${encode(language)}&format=json"
}

interface GeocodingEndpoint {
    suspend fun search(query: String, language: String): List<PlaceSearchResult>
}

class OpenMeteoGeocoder(
    private val baseUrl: String = GEOCODING_BASE_URL,
) : GeocodingEndpoint {
    override suspend fun search(query: String, language: String): List<PlaceSearchResult> =
        withContext(Dispatchers.IO) {
            val connection = URL(buildGeocodingUrl(query, language, baseUrl))
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
                check(connection.responseCode in 200..299) {
                    "Place search returned ${connection.responseCode}"
                }
                GeocodingMapper.parse(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
}

@Serializable
private data class GeocodingResponse(val results: List<GeocodingItem> = emptyList())

@Serializable
private data class GeocodingItem(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val admin1: String? = null,
    val country: String? = null,
)

object GeocodingMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<PlaceSearchResult> =
        json.decodeFromString<GeocodingResponse>(body).results.mapNotNull { item ->
            runCatching {
                val place = SavedPlace(item.name, item.latitude, item.longitude)
                val detail = listOfNotNull(
                    item.admin1?.takeIf { it.isNotBlank() && it != item.name },
                    item.country?.takeIf { it.isNotBlank() },
                ).distinct().joinToString(", ")
                PlaceSearchResult(place.name, detail, place.latitude, place.longitude)
            }.getOrNull()
        }
}
