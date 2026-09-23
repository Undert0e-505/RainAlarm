package com.rainalarm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com/v1/search"
private const val POSTCODE_BASE_URL = "https://api.postcodes.io/postcodes"
private const val GEOCODING_CONNECT_TIMEOUT_MS = 8_000
private const val GEOCODING_READ_TIMEOUT_MS = 10_000

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

data class GeocodingHttpResponse(val statusCode: Int, val body: String)

fun interface GeocodingHttpClient {
    suspend fun get(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): GeocodingHttpResponse
}

object UrlConnectionGeocodingHttpClient : GeocodingHttpClient {
    override suspend fun get(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): GeocodingHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            GeocodingHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

class OpenMeteoGeocoder(
    private val baseUrl: String = GEOCODING_BASE_URL,
    private val httpClient: GeocodingHttpClient = UrlConnectionGeocodingHttpClient,
) : GeocodingEndpoint {
    override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
        val response = httpClient.get(
            buildGeocodingUrl(query, language, baseUrl),
            GEOCODING_CONNECT_TIMEOUT_MS,
            GEOCODING_READ_TIMEOUT_MS,
        )
        check(response.statusCode in 200..299) {
            "Place search returned ${response.statusCode}"
        }
        return GeocodingMapper.parse(response.body)
    }
}

/** Routes only complete UK postcode shapes away from the existing town/place provider. */
class PlaceGeocoder(
    private val placeSearch: GeocodingEndpoint = OpenMeteoGeocoder(),
    private val postcodeSearch: GeocodingEndpoint = PostcodesIoGeocoder(),
) : GeocodingEndpoint {
    override suspend fun search(query: String, language: String): List<PlaceSearchResult> =
        if (UkPostcodePolicy.normalize(query) != null) postcodeSearch.search(query, language)
        else placeSearch.search(query, language)
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

object UkPostcodePolicy {
    private val fullPostcode = Regex(
        "^(?:GIR\\s?0AA|[A-Z]{1,2}[0-9][A-Z0-9]?\\s?[0-9][ABD-HJLNP-UW-Z]{2})$",
    )

    fun normalize(query: String): String? {
        val candidate = query.trim().uppercase(Locale.ROOT)
        if (!fullPostcode.matches(candidate)) return null
        val compact = candidate.replace(Regex("\\s"), "")
        return compact.dropLast(3) + " " + compact.takeLast(3)
    }
}

fun buildPostcodeLookupUrl(
    query: String,
    baseUrl: String = POSTCODE_BASE_URL,
): String {
    val postcode = requireNotNull(UkPostcodePolicy.normalize(query)) { "A full UK postcode is required" }
    val endpoint = URL(baseUrl)
    require(endpoint.protocol == "https" && endpoint.host.isNotBlank() &&
        endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null) {
        "Postcode endpoint must be a plain HTTPS URL"
    }
    val encoded = URLEncoder.encode(postcode, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "${baseUrl.trimEnd('/')}/$encoded"
}

class PostcodesIoGeocoder(
    private val baseUrl: String = POSTCODE_BASE_URL,
    private val httpClient: GeocodingHttpClient = UrlConnectionGeocodingHttpClient,
) : GeocodingEndpoint {
    override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
        val response = httpClient.get(
            buildPostcodeLookupUrl(query, baseUrl),
            GEOCODING_CONNECT_TIMEOUT_MS,
            GEOCODING_READ_TIMEOUT_MS,
        )
        if (response.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return emptyList()
        check(response.statusCode in 200..299) {
            "Postcode search returned ${response.statusCode}"
        }
        return PostcodesIoMapper.parse(response.body)
    }
}

@Serializable
private data class PostcodesIoResponse(
    val status: Int,
    val result: PostcodesIoItem? = null,
)

@Serializable
private data class PostcodesIoItem(
    val postcode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val parish: String? = null,
    val admin_district: String? = null,
    val admin_county: String? = null,
    val country: String? = null,
)

object PostcodesIoMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<PlaceSearchResult> {
        val response = json.decodeFromString<PostcodesIoResponse>(body)
        if (response.status == HttpURLConnection.HTTP_NOT_FOUND || response.result == null) {
            return emptyList()
        }
        require(response.status in 200..299) { "Postcode response reported ${response.status}" }
        val item = response.result
        val postcode = item.postcode?.let(UkPostcodePolicy::normalize) ?: return emptyList()
        val latitude = item.latitude ?: return emptyList()
        val longitude = item.longitude ?: return emptyList()
        return runCatching {
            val place = SavedPlace(postcode, latitude, longitude)
            val detail = listOf(item.parish, item.admin_district, item.admin_county, item.country)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .joinToString(", ")
            listOf(PlaceSearchResult(place.name, detail, place.latitude, place.longitude))
        }.getOrDefault(emptyList())
    }
}
