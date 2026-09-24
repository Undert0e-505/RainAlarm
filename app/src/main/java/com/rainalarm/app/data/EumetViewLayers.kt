package com.rainalarm.app.data

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** Exact EUMETView product identity; FOG remains only the legacy persisted UI-layer key. */
enum class EumetProduct(
    val layerName: String,
    val conceptualLayer: RadarMapLayer,
    val maxAgeSeconds: Long,
    val nominalCadenceSeconds: Long,
) {
    LIGHTNING("mtg_fd:li_afa", RadarMapLayer.LIGHTNING, 1_800L, 300L),
    CLOUD_TYPE("mtg_fd:rgb_cloudtype", RadarMapLayer.FOG, 3_600L, 600L),
    FOG_LOW_CLOUD("mtg_fd:rgb_fog", RadarMapLayer.FOG, 3_600L, 600L),
    ;

    companion object {
        fun defaultFor(choice: RadarMapLayer): EumetProduct = when (choice) {
            RadarMapLayer.LIGHTNING -> LIGHTNING
            RadarMapLayer.FOG -> FOG_LOW_CLOUD
            else -> error("Not a satellite layer")
        }
    }
}

data class EumetLayerMetadata(
    val choice: RadarMapLayer,
    val validEpochSeconds: Long,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val product: EumetProduct = EumetProduct.defaultFor(choice),
    val availableFromEpochSeconds: Long = validEpochSeconds,
    val latestEpochSeconds: Long = validEpochSeconds,
    val cadenceSeconds: Long = product.nominalCadenceSeconds,
) {
    init {
        require(product.conceptualLayer == choice)
        require(availableFromEpochSeconds <= validEpochSeconds &&
            validEpochSeconds <= latestEpochSeconds)
        require(cadenceSeconds in 60L..21_600L)
    }

    fun covers(place: SavedPlace): Boolean = place.latitude in south..north && place.longitude in west..east
    fun freshAt(now: Long): Boolean = now - latestEpochSeconds in -300L..product.maxAgeSeconds
    val layerName: String get() = product.layerName
    val frameIdentity: String get() = "${product.name}:$validEpochSeconds"

    /** Latest advertised observation at or before the absolute radar cursor. */
    fun frameAtOrBefore(cursorEpochSeconds: Long): EumetLayerMetadata? {
        if (cursorEpochSeconds < availableFromEpochSeconds) return null
        val selected = if (cursorEpochSeconds >= latestEpochSeconds) latestEpochSeconds else {
            availableFromEpochSeconds +
                (cursorEpochSeconds - availableFromEpochSeconds) / cadenceSeconds * cadenceSeconds
        }
        return copy(validEpochSeconds = selected.coerceAtMost(latestEpochSeconds))
    }

    /** MapLibre's TileSet preserves this substitution token (raw URL constructors may not). */
    fun tileUrl(): String = "https://view.eumetsat.int/geoserver/wms?service=WMS&version=1.1.1" +
        "&request=GetMap&layers=${URLEncoder.encode(layerName, "UTF-8")}" +
        "&styles=&format=image%2Fpng&transparent=true&srs=EPSG%3A3857" +
        "&bbox={bbox-epsg-3857}&width=256&height=256" +
        "&time=${URLEncoder.encode(Instant.ofEpochSecond(validEpochSeconds).toString(), "UTF-8")}"

    fun probeUrl(place: SavedPlace): String {
        val radius = 6_378_137.0
        val latitude = Math.toRadians(place.latitude.coerceIn(-85.0, 85.0))
        val x = radius * Math.toRadians(place.longitude)
        val y = radius * ln(tan(Math.PI / 4 + latitude / 2))
        val bbox = String.format(Locale.ROOT, "%.0f,%.0f,%.0f,%.0f",
            x - 25_000, y - 25_000, x + 25_000, y + 25_000)
        return tileUrl().replace("{bbox-epsg-3857}", bbox)
            .replace("&width=256&height=256", "&width=64&height=64")
    }
}

/** Selects a useful cloud product even when Open-Meteo daylight is absent or stale. */
object CloudProductSelectionPolicy {
    fun preferred(
        weather: CurrentWeather?,
        place: SavedPlace,
        nowEpochSeconds: Long,
    ): EumetProduct {
        val daylight = weather?.takeIf { it.daylightFreshAt(nowEpochSeconds) }?.isDay
            ?: weather?.let { solarEventsDaylight(it, nowEpochSeconds) }
            ?: astronomicalDaylight(place.latitude, place.longitude, nowEpochSeconds)
        return if (daylight) EumetProduct.CLOUD_TYPE else EumetProduct.FOG_LOW_CLOUD
    }

    fun loadOrder(preferred: EumetProduct): List<EumetProduct> {
        require(preferred.conceptualLayer == RadarMapLayer.FOG)
        val fallback = if (preferred == EumetProduct.CLOUD_TYPE) {
            EumetProduct.FOG_LOW_CLOUD
        } else {
            EumetProduct.CLOUD_TYPE
        }
        return listOf(preferred, fallback)
    }

    private fun solarEventsDaylight(weather: CurrentWeather, now: Long): Boolean? {
        val sunrise = weather.sunriseEpochSeconds ?: return null
        val sunset = weather.sunsetEpochSeconds ?: return null
        if (sunrise >= sunset) return null
        val zone = weather.timeZone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
            ?: return null
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        if (Instant.ofEpochSecond(sunrise).atZone(zone).toLocalDate() != today ||
            Instant.ofEpochSecond(sunset).atZone(zone).toLocalDate() != today) return null
        return now in sunrise until sunset
    }

    /** NOAA-style solar-position approximation, bounded away from singular pole latitudes. */
    internal fun astronomicalDaylight(latitude: Double, longitude: Double, now: Long): Boolean {
        val utc = Instant.ofEpochSecond(now).atOffset(ZoneOffset.UTC)
        val minutes = utc.hour * 60.0 + utc.minute + utc.second / 60.0
        val gamma = 2.0 * PI / 365.0 * (utc.dayOfYear - 1 + (minutes / 60.0 - 12.0) / 24.0)
        val equationMinutes = 229.18 * (0.000075 + 0.001868 * cos(gamma) -
            0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val trueSolarMinutes = ((minutes + equationMinutes + 4.0 *
            longitude.coerceIn(-180.0, 180.0)) % 1_440.0 + 1_440.0) % 1_440.0
        val hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0)
        val latitudeRadians = Math.toRadians(latitude.coerceIn(-89.8, 89.8))
        val sineAltitude = sin(latitudeRadians) * sin(declination) +
            cos(latitudeRadians) * cos(declination) * cos(hourAngle)
        return sineAltitude > sin(Math.toRadians(-0.833))
    }
}

/** Pure cursor-to-observation selection shared by Lightning and the two Clouds products. */
object SatelliteFrameSelectionPolicy {
    fun lightning(metadata: Collection<EumetLayerMetadata>, cursorEpochSeconds: Long): EumetLayerMetadata? =
        metadata.firstOrNull { it.product == EumetProduct.LIGHTNING }
            ?.frameAtOrBefore(cursorEpochSeconds)

    fun clouds(
        metadata: Collection<EumetLayerMetadata>,
        weather: CurrentWeather?,
        place: SavedPlace,
        cursorEpochSeconds: Long,
    ): EumetLayerMetadata? {
        val candidates = metadata.filter { it.choice == RadarMapLayer.FOG }
            .mapNotNull { it.frameAtOrBefore(cursorEpochSeconds) }
        if (candidates.isEmpty()) return null
        // During radar forecast time, choose day/night using the held latest satellite
        // observation rather than a future cursor for which no satellite image exists.
        val effectiveFrameTime = candidates.maxOf(EumetLayerMetadata::validEpochSeconds)
        val preferred = CloudProductSelectionPolicy.preferred(weather, place, effectiveFrameTime)
        return candidates.firstOrNull { it.product == preferred }
            ?: candidates.maxByOrNull(EumetLayerMetadata::validEpochSeconds)
    }
}

object EumetCacheIdentity {
    fun metadata(product: EumetProduct): String = product.name
    fun probe(product: EumetProduct, validTime: Long, place: SavedPlace): String =
        "${product.name}:$validTime:${floor(place.latitude * 10)}:${floor(place.longitude * 10)}"
}

internal object EumetTimeDimensionCodec {
    private const val MAX_RANGE_SECONDS = 5L * 366L * 24L * 60L * 60L

    data class Parsed(val startEpochSeconds: Long, val endEpochSeconds: Long, val cadenceSeconds: Long)

    fun parse(raw: String): Parsed {
        val parts = raw.trim().split('/').map(String::trim)
        require(parts.size == 3) { "Satellite time dimension is malformed" }
        val start = Instant.parse(parts[0]).epochSecond
        val end = Instant.parse(parts[1]).epochSecond
        val duration = Duration.parse(parts[2])
        require(!duration.isNegative && !duration.isZero && duration.nano == 0) {
            "Satellite cadence is invalid"
        }
        val cadence = duration.seconds
        require(cadence in 60L..21_600L) { "Satellite cadence is outside supported bounds" }
        require(end >= start && end - start <= MAX_RANGE_SECONDS) {
            "Satellite time range is outside supported bounds"
        }
        return Parsed(start, end, cadence)
    }
}

internal object EumetXmlSecurity {
    private val forbiddenDeclaration = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
    private val optionalFeatures = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        XMLConstants.FEATURE_SECURE_PROCESSING to true,
    )

    /** Android's DOM factory supports fewer hardening feature URIs than the JVM Xerces factory. */
    fun applyOptionalFeatures(setFeature: (String, Boolean) -> Unit) {
        optionalFeatures.forEach { (name, value) ->
            try {
                setFeature(name, value)
            } catch (_: ParserConfigurationException) {
                // The bounded UTF-8 input and declaration rejection below remain mandatory.
            }
        }
    }

    fun validatedUtf8(body: ByteArray): String {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
                .removePrefix("\uFEFF")
        } catch (failure: CharacterCodingException) {
            throw IllegalArgumentException("Satellite capabilities are not valid UTF-8", failure)
        }
        require(!forbiddenDeclaration.containsMatchIn(text)) {
            "Satellite capabilities must not contain document type or entity declarations"
        }
        return text
    }
}

object EumetCapabilities {
    fun parse(body: ByteArray, product: EumetProduct): EumetLayerMetadata {
        require(body.size <= 1024 * 1024)
        val wanted = product.layerName
        val xml = EumetXmlSecurity.validatedUtf8(body)
        val factory = DocumentBuilderFactory.newInstance()
        EumetXmlSecurity.applyOptionalFeatures(factory::setFeature)
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML resources are disabled") }
        }
        val document = builder.parse(InputSource(StringReader(xml)))
        val layers = document.getElementsByTagName("Layer")
        val layer = (0 until layers.length).mapNotNull { layers.item(it) as? Element }.firstOrNull { element ->
            element.directChild("Name")?.textContent?.trim() == wanted
        } ?: error("Satellite layer unavailable")
        val dimension = (0 until layer.getElementsByTagName("Dimension").length)
            .mapNotNull { layer.getElementsByTagName("Dimension").item(it) as? Element }
            .firstOrNull { it.getAttribute("name") == "time" } ?: error("Satellite layer has no valid time")
        val time = EumetTimeDimensionCodec.parse(dimension.textContent)
        val bounds = layer.directChild("EX_GeographicBoundingBox") ?: error("Satellite bounds missing")
        fun coordinate(tag: String): Double = bounds.getElementsByTagName(tag).item(0)?.textContent?.toDouble()
            ?: error("Satellite bounds incomplete")
        return EumetLayerMetadata(product.conceptualLayer, time.endEpochSeconds,
            coordinate("westBoundLongitude"),
            coordinate("southBoundLatitude"), coordinate("eastBoundLongitude"),
            coordinate("northBoundLatitude"), product, time.startEpochSeconds,
            time.endEpochSeconds, time.cadenceSeconds)
    }

    fun parse(body: ByteArray, choice: RadarMapLayer): EumetLayerMetadata =
        parse(body, EumetProduct.defaultFor(choice))

    private fun Element.directChild(tag: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.firstOrNull { it.tagName == tag }
}

object EumetViewRepository {
    private val metadataCache = mutableMapOf<EumetProduct, Pair<Long, EumetLayerMetadata>>()
    private val probeCache = LinkedHashMap<String, Long>()
    private val mutex = Mutex()
    private const val HOST = "view.eumetsat.int"

    suspend fun metadata(
        product: EumetProduct,
        place: SavedPlace,
        force: Boolean = false,
    ): EumetLayerMetadata = mutex.withLock {
        val now = Instant.now().epochSecond
        val cached = metadataCache[product]
        val value = if (!force && cached != null && now - cached.first in 0..300) cached.second else {
            val bytes = fetch("https://$HOST/geoserver/wms?service=WMS&version=1.3.0&request=GetCapabilities", 1024 * 1024, "xml")
            EumetCapabilities.parse(bytes, product).also { metadataCache[product] = now to it }
        }
        verifyFrameLocked(value, place, force, now)
    }

    suspend fun metadata(
        choice: RadarMapLayer,
        place: SavedPlace,
        force: Boolean = false,
    ): EumetLayerMetadata = metadata(EumetProduct.defaultFor(choice), place, force)

    suspend fun clouds(
        preferred: EumetProduct,
        place: SavedPlace,
        force: Boolean = false,
    ): EumetLayerMetadata {
        var lastFailure: Exception? = null
        for (product in CloudProductSelectionPolicy.loadOrder(preferred)) {
            try {
                return metadata(product, place, force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IllegalStateException("Cloud imagery unavailable", lastFailure)
    }

    /** Loads both products when available so timeline scrubbing can cross day/night. */
    suspend fun cloudProducts(
        preferred: EumetProduct,
        place: SavedPlace,
        force: Boolean = false,
    ): List<EumetLayerMetadata> {
        val results = mutableListOf<EumetLayerMetadata>()
        var lastFailure: Exception? = null
        for (product in CloudProductSelectionPolicy.loadOrder(preferred)) {
            try {
                results += metadata(product, place, force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        if (results.isEmpty()) throw IllegalStateException("Cloud imagery unavailable", lastFailure)
        return results
    }

    private suspend fun verifyFrameLocked(
        value: EumetLayerMetadata,
        place: SavedPlace,
        force: Boolean,
        now: Long,
    ): EumetLayerMetadata {
        require(value.freshAt(now)) { "Satellite image is delayed" }
        require(value.covers(place)) { "Outside satellite coverage" }
        require(value.validEpochSeconds in value.availableFromEpochSeconds..value.latestEpochSeconds)
        val probeKey = EumetCacheIdentity.probe(value.product, value.validEpochSeconds, place)
        if (force || now - (probeCache[probeKey] ?: 0L) !in 0L..300L) {
            val probe = fetch(value.probeUrl(place), 512 * 1024, "image/png")
            require(probe.size >= 8 && probe.take(8).toByteArray().contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Satellite image response is invalid" }
            probeCache[probeKey] = now
            if (probeCache.size > 32) probeCache.remove(probeCache.keys.first())
        }
        return value
    }

    private suspend fun fetch(rawUrl: String, limit: Int, expected: String): ByteArray = withContext(Dispatchers.IO) {
        val url = URL(rawUrl)
        require(url.protocol == "https" && url.host == HOST)
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        try {
            require(connection.responseCode == 200)
            require(connection.contentType?.lowercase()?.contains(expected) == true)
            require(connection.contentLengthLong in -1..limit.toLong())
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(out.size() + read <= limit)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
