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
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

data class EumetLayerMetadata(
    val choice: RadarMapLayer,
    val validEpochSeconds: Long,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    fun covers(place: SavedPlace): Boolean = place.latitude in south..north && place.longitude in west..east
    fun freshAt(now: Long): Boolean = now - validEpochSeconds in -300L..when (choice) {
        RadarMapLayer.LIGHTNING -> 1_800L
        RadarMapLayer.FOG -> 3_600L
        else -> 0L
    }
    val layerName: String get() = when (choice) {
        RadarMapLayer.LIGHTNING -> "mtg_fd:li_afa"
        RadarMapLayer.FOG -> "mtg_fd:rgb_fog"
        else -> error("Not a satellite layer")
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
    fun parse(body: ByteArray, choice: RadarMapLayer): EumetLayerMetadata {
        require(body.size <= 1024 * 1024)
        val wanted = when (choice) {
            RadarMapLayer.LIGHTNING -> "mtg_fd:li_afa"
            RadarMapLayer.FOG -> "mtg_fd:rgb_fog"
            else -> error("Not a satellite layer")
        }
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
        val time = Instant.parse(dimension.textContent.trim().split('/').getOrNull(1)?.trim()
            ?: error("Satellite time range missing")).epochSecond
        val bounds = layer.directChild("EX_GeographicBoundingBox") ?: error("Satellite bounds missing")
        fun coordinate(tag: String): Double = bounds.getElementsByTagName(tag).item(0)?.textContent?.toDouble()
            ?: error("Satellite bounds incomplete")
        return EumetLayerMetadata(choice, time, coordinate("westBoundLongitude"),
            coordinate("southBoundLatitude"), coordinate("eastBoundLongitude"), coordinate("northBoundLatitude"))
    }

    private fun Element.directChild(tag: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.firstOrNull { it.tagName == tag }
}

object EumetViewRepository {
    private val metadataCache = mutableMapOf<RadarMapLayer, Pair<Long, EumetLayerMetadata>>()
    private val probeCache = LinkedHashMap<String, Long>()
    private val mutex = Mutex()
    private const val HOST = "view.eumetsat.int"

    suspend fun metadata(choice: RadarMapLayer, place: SavedPlace, force: Boolean = false): EumetLayerMetadata = mutex.withLock {
        require(choice == RadarMapLayer.LIGHTNING || choice == RadarMapLayer.FOG)
        val now = Instant.now().epochSecond
        val cached = metadataCache[choice]
        val value = if (!force && cached != null && now - cached.first in 0..300) cached.second else {
            val bytes = fetch("https://$HOST/geoserver/wms?service=WMS&version=1.3.0&request=GetCapabilities", 1024 * 1024, "xml")
            EumetCapabilities.parse(bytes, choice).also { metadataCache[choice] = now to it }
        }
        require(value.freshAt(now)) { "Satellite image is delayed" }
        require(value.covers(place)) { "Outside satellite coverage" }
        val probeKey = "${choice.name}:${value.validEpochSeconds}:${floor(place.latitude * 10)}:${floor(place.longitude * 10)}"
        if (force || now - (probeCache[probeKey] ?: 0L) !in 0L..300L) {
            val probe = fetch(value.probeUrl(place), 512 * 1024, "image/png")
            require(probe.size >= 8 && probe.take(8).toByteArray().contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Satellite image response is invalid" }
            probeCache[probeKey] = now
            if (probeCache.size > 32) probeCache.remove(probeCache.keys.first())
        }
        value
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
