package com.rainalarm.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
private const val PHOTON_BASE_URL = "https://photon.komoot.io/api/"
private const val WIKIMEDIA_BASE_URL = "https://en.wikipedia.org/w/api.php"
private const val GEOCODING_CONNECT_TIMEOUT_MS = 8_000
private const val GEOCODING_READ_TIMEOUT_MS = 10_000
private const val PHOTON_CONNECT_TIMEOUT_MS = 5_000
private const val PHOTON_READ_TIMEOUT_MS = 7_000
private const val WIKIMEDIA_CONNECT_TIMEOUT_MS = 4_000
private const val WIKIMEDIA_READ_TIMEOUT_MS = 6_000
private const val PLACE_RESULT_LIMIT = 8
private const val PHOTON_RESULT_LIMIT = 6
private const val WIKIMEDIA_RESULT_LIMIT = 5

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

fun buildPhotonGeocodingUrl(
    query: String,
    language: String,
    baseUrl: String = PHOTON_BASE_URL,
): String {
    val trimmed = query.trim()
    require(trimmed.length >= 2) { "Search needs at least two characters" }
    val endpoint = URL(baseUrl)
    require(endpoint.protocol == "https" && endpoint.host.isNotBlank() &&
        endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null) {
        "Photon endpoint must be a plain HTTPS URL"
    }
    fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    return "${baseUrl.trimEnd('/')}?q=${encode(trimmed)}&limit=$PHOTON_RESULT_LIMIT&lang=${encode(language)}"
}

/**
 * Builds the low-volume fallback query against English Wikipedia. Keeping one audited host avoids
 * constructing a host name from an untrusted locale while still giving worldwide named-place
 * coverage. Search text is normalized so straight and curly possessives share one request.
 */
fun buildWikimediaGeocodingUrl(
    query: String,
    baseUrl: String = WIKIMEDIA_BASE_URL,
): String {
    val normalized = PlaceSearchText.normalize(query)
    require(normalized.length >= 2) { "Search needs at least two characters" }
    val endpoint = URL(baseUrl)
    require(endpoint.protocol == "https" && endpoint.host.isNotBlank() &&
        endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null) {
        "Wikimedia endpoint must be a plain HTTPS URL"
    }
    fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    return "${baseUrl.trimEnd('/')}?action=query&format=json&formatversion=2" +
        "&generator=search&gsrsearch=${encode(normalized)}&gsrlimit=$WIKIMEDIA_RESULT_LIMIT" +
        "&gsrnamespace=0&prop=coordinates%7Cdescription&coprimary=all&redirects=1"
}

internal object PlaceSearchText {
    private val singularPossessive = Regex("(?iu)([\\p{L}\\p{N}])['’‘ʼ]s\\b")
    private val pluralPossessive = Regex("(?iu)([\\p{L}\\p{N}]s)['’‘ʼ]\\b")

    fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(singularPossessive, "$1")
        .replace(pluralPossessive, "$1")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(String::isNotBlank)
        .joinToString(" ")

    fun words(value: String): LinkedHashSet<String> = normalize(value)
        .split(' ')
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())
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
            connection.setRequestProperty(
                "User-Agent",
                "RainAlarm Android (https://github.com/Undert0e-505/RainAlarm)",
            )
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            GeocodingHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Searches OSM named features through Photon. Results are cached and concurrent identical requests
 * share one public-service request; this keeps explicit retries and double taps inexpensive.
 */
class PhotonGeocoder(
    private val baseUrl: String = PHOTON_BASE_URL,
    private val httpClient: GeocodingHttpClient = UrlConnectionGeocodingHttpClient,
    private val cacheCapacity: Int = 12,
) : GeocodingEndpoint {
    private val mutex = Mutex()
    private val cached = LinkedHashMap<String, List<PlaceSearchResult>>(cacheCapacity, 0.75f, true)
    private val inFlight = mutableMapOf<String, Deferred<List<PlaceSearchResult>>>()

    override suspend fun search(query: String, language: String): List<PlaceSearchResult> =
        coroutineScope {
            val key = "${PlaceSearchText.normalize(query)}|${language.lowercase(Locale.ROOT)}"
            mutex.withLock { cached[key] }?.let { return@coroutineScope it }

            var ownsRequest = false
            val request = mutex.withLock {
                cached[key]?.let { return@withLock null }
                inFlight[key] ?: async {
                    val response = httpClient.get(
                        buildPhotonGeocodingUrl(query, language, baseUrl),
                        PHOTON_CONNECT_TIMEOUT_MS,
                        PHOTON_READ_TIMEOUT_MS,
                    )
                    check(response.statusCode in 200..299) {
                        "Named-place search returned ${response.statusCode}"
                    }
                    PhotonGeocodingMapper.parse(response.body).take(PHOTON_RESULT_LIMIT)
                }.also {
                    ownsRequest = true
                    inFlight[key] = it
                }
            }
            if (request == null) return@coroutineScope mutex.withLock { cached[key].orEmpty() }

            try {
                val results = request.await()
                if (ownsRequest) mutex.withLock {
                    cached[key] = results
                    while (cached.size > cacheCapacity) {
                        cached.remove(cached.entries.first().key)
                    }
                }
                results
            } finally {
                if (ownsRequest) mutex.withLock {
                    if (inFlight[key] === request) inFlight.remove(key)
                }
            }
        }
}

/**
 * Resolves notable geocoded pages only when the primary place services have no strong match.
 * Identical normalized explicit searches share one bounded public-service request.
 */
class WikimediaGeocoder(
    private val baseUrl: String = WIKIMEDIA_BASE_URL,
    private val httpClient: GeocodingHttpClient = UrlConnectionGeocodingHttpClient,
    private val cacheCapacity: Int = 8,
) : GeocodingEndpoint {
    private val mutex = Mutex()
    private val cached = LinkedHashMap<String, List<PlaceSearchResult>>(cacheCapacity, 0.75f, true)
    private val inFlight = mutableMapOf<String, Deferred<List<PlaceSearchResult>>>()

    override suspend fun search(query: String, language: String): List<PlaceSearchResult> =
        coroutineScope {
            val key = PlaceSearchText.normalize(query)
            mutex.withLock { cached[key] }?.let { return@coroutineScope it }

            var ownsRequest = false
            val request = mutex.withLock {
                cached[key]?.let { return@withLock null }
                inFlight[key] ?: async {
                    val response = httpClient.get(
                        buildWikimediaGeocodingUrl(query, baseUrl),
                        WIKIMEDIA_CONNECT_TIMEOUT_MS,
                        WIKIMEDIA_READ_TIMEOUT_MS,
                    )
                    check(response.statusCode in 200..299) {
                        "Notable-place search returned ${response.statusCode}"
                    }
                    WikimediaGeocodingMapper.parse(response.body).take(WIKIMEDIA_RESULT_LIMIT)
                }.also {
                    ownsRequest = true
                    inFlight[key] = it
                }
            }
            if (request == null) return@coroutineScope mutex.withLock { cached[key].orEmpty() }

            try {
                val results = request.await()
                if (ownsRequest) mutex.withLock {
                    cached[key] = results
                    while (cached.size > cacheCapacity) cached.remove(cached.entries.first().key)
                }
                results
            } finally {
                if (ownsRequest) mutex.withLock {
                    if (inFlight[key] === request) inFlight.remove(key)
                }
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
    private val namedPlaceSearch: GeocodingEndpoint = PhotonGeocoder(),
    private val notablePlaceSearch: GeocodingEndpoint = WikimediaGeocoder(),
) : GeocodingEndpoint {
    override suspend fun search(query: String, language: String): List<PlaceSearchResult> {
        if (UkPostcodePolicy.normalize(query) != null) return postcodeSearch.search(query, language)
        return supervisorScope {
            val townRequest = async { runCatching { placeSearch.search(query, language) } }
            val namedRequest = async { runCatching { namedPlaceSearch.search(query, language) } }
            val townResult = townRequest.await()
            val namedResult = namedRequest.await()
            val primary = PlaceSearchMerger.merge(
                query = query,
                townResults = townResult.getOrDefault(emptyList()),
                namedResults = namedResult.getOrDefault(emptyList()),
            )
            val notableResult = if (PlaceSearchMerger.hasStrongMatch(query, primary)) {
                Result.success(emptyList())
            } else {
                runCatching { notablePlaceSearch.search(query, language) }
            }
            if (townResult.isFailure && namedResult.isFailure && notableResult.isFailure) {
                throw townResult.exceptionOrNull() ?: namedResult.exceptionOrNull()
                ?: notableResult.exceptionOrNull() ?: IllegalStateException("Place search failed")
            }
            PlaceSearchMerger.merge(
                query = query,
                townResults = townResult.getOrDefault(emptyList()),
                namedResults = namedResult.getOrDefault(emptyList()),
                notableResults = notableResult.getOrDefault(emptyList()),
            )
        }
    }
}

object PlaceSearchMerger {
    fun merge(
        query: String,
        townResults: List<PlaceSearchResult>,
        namedResults: List<PlaceSearchResult>,
        notableResults: List<PlaceSearchResult> = emptyList(),
        limit: Int = PLACE_RESULT_LIMIT,
    ): List<PlaceSearchResult> {
        val queryWords = words(query)
        val candidates = buildList {
            townResults.forEach { add(SearchCandidate(it, 0)) }
            namedResults.forEach { add(SearchCandidate(it, 0)) }
            // Wikimedia is queried only after the primary services proved weak. Its search rank
            // is therefore a useful source-confidence signal for notable named places.
            notableResults.forEach { add(SearchCandidate(it, 1_000)) }
        }
        return candidates
            .mapIndexed { index, candidate ->
                RankedResult(candidate.result, score(candidate.result, queryWords) + candidate.sourceBonus, index)
            }
            .sortedWith(compareByDescending<RankedResult> { it.score }.thenBy { it.index })
            .fold(mutableListOf()) { accepted, ranked ->
                val duplicate = accepted.any { existing ->
                    existing.name.equals(ranked.result.name, ignoreCase = true) &&
                        squaredDistance(existing, ranked.result) < 0.0004
                }
                if (!duplicate && accepted.size < limit) accepted += ranked.result
                accepted
            }
    }

    fun hasStrongMatch(query: String, results: List<PlaceSearchResult>): Boolean {
        val queryWords = words(query)
        if (queryWords.isEmpty()) return false
        val normalizedQuery = queryWords.joinToString(" ")
        return results.any { result ->
            val nameWords = words(result.name)
            val normalizedName = nameWords.joinToString(" ")
            normalizedName == normalizedQuery ||
                normalizedName.startsWith("$normalizedQuery ") ||
                (queryWords.size > 1 && nameWords.containsAll(queryWords))
        }
    }

    private fun score(result: PlaceSearchResult, queryWords: Set<String>): Int {
        val nameWords = words(result.name)
        val detailWords = words(result.detail)
        val overlap = queryWords.count { it in nameWords } * 50 +
            queryWords.count { it in detailWords } * 15
        val normalizedQuery = queryWords.joinToString(" ")
        val normalizedName = nameWords.joinToString(" ")
        return overlap + when {
            normalizedName == normalizedQuery -> 1_000
            normalizedName.startsWith(normalizedQuery) -> 800
            nameWords.isNotEmpty() && queryWords.containsAll(nameWords) -> 600
            else -> 0
        }
    }

    private fun words(value: String): Set<String> = PlaceSearchText.words(value)

    private fun squaredDistance(a: PlaceSearchResult, b: PlaceSearchResult): Double {
        val latitude = a.latitude - b.latitude
        val longitude = a.longitude - b.longitude
        return latitude * latitude + longitude * longitude
    }

    private data class RankedResult(
        val result: PlaceSearchResult,
        val score: Int,
        val index: Int,
    )

    private data class SearchCandidate(val result: PlaceSearchResult, val sourceBonus: Int)
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

@Serializable
private data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
private data class PhotonFeature(
    val properties: PhotonProperties = PhotonProperties(),
    val geometry: PhotonGeometry? = null,
)

@Serializable
private data class PhotonProperties(
    val name: String? = null,
    val locality: String? = null,
    val district: String? = null,
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
private data class PhotonGeometry(val coordinates: List<Double> = emptyList())

object PhotonGeocodingMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<PlaceSearchResult> =
        json.decodeFromString<PhotonResponse>(body).features.mapNotNull { feature ->
            val name = feature.properties.name?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val coordinates = feature.geometry?.coordinates ?: return@mapNotNull null
            if (coordinates.size < 2) return@mapNotNull null
            runCatching {
                val place = SavedPlace(name, coordinates[1], coordinates[0])
                val detail = listOf(
                    feature.properties.locality,
                    feature.properties.district,
                    feature.properties.city,
                    feature.properties.county,
                    feature.properties.state,
                    feature.properties.country,
                ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                    .filterNot { it.equals(name, ignoreCase = true) }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .joinToString(", ")
                PlaceSearchResult(place.name, detail, place.latitude, place.longitude)
            }.getOrNull()
        }
}

@Serializable
private data class WikimediaResponse(val query: WikimediaQuery? = null)

@Serializable
private data class WikimediaQuery(val pages: List<WikimediaPage> = emptyList())

@Serializable
private data class WikimediaPage(
    val title: String = "",
    val description: String? = null,
    val coordinates: List<WikimediaCoordinate> = emptyList(),
)

@Serializable
private data class WikimediaCoordinate(val lat: Double? = null, val lon: Double? = null)

object WikimediaGeocodingMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<PlaceSearchResult> =
        json.decodeFromString<WikimediaResponse>(body).query?.pages.orEmpty().mapNotNull { page ->
            val title = page.title.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val coordinate = page.coordinates.firstOrNull { value ->
                value.lat?.isFinite() == true && value.lon?.isFinite() == true &&
                    value.lat in -90.0..90.0 && value.lon in -180.0..180.0
            } ?: return@mapNotNull null
            runCatching {
                val place = SavedPlace(title, requireNotNull(coordinate.lat), requireNotNull(coordinate.lon))
                PlaceSearchResult(
                    place.name,
                    page.description?.trim().orEmpty(),
                    place.latitude,
                    place.longitude,
                )
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
