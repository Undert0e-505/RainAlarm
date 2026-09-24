package com.rainalarm.app.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Geographic rectangle. A west value greater than east represents an antimeridian crossing. */
data class SatelliteGeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west in -180.0..180.0 && east in -180.0..180.0)
        require(south in -90.0..90.0 && north in -90.0..90.0 && south <= north)
    }

    val crossesAntimeridian: Boolean get() = west > east

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude !in south..north) return false
        val normalized = longitude.coerceIn(-180.0, 180.0)
        return if (crossesAntimeridian) normalized >= west || normalized <= east
        else normalized in west..east
    }

    fun intersections(other: SatelliteGeoBounds): List<SatelliteGeoBounds> {
        val overlapSouth = max(south, other.south)
        val overlapNorth = min(north, other.north)
        if (overlapSouth > overlapNorth) return emptyList()
        return longitudeSegments().flatMap { first ->
            other.longitudeSegments().mapNotNull { second ->
                val overlapWest = max(first.first, second.first)
                val overlapEast = min(first.second, second.second)
                if (overlapWest > overlapEast) null else SatelliteGeoBounds(
                    overlapWest, overlapSouth, overlapEast, overlapNorth,
                )
            }
        }
    }

    private fun longitudeSegments(): List<Pair<Double, Double>> = if (crossesAntimeridian) {
        listOf(west to 180.0, -180.0 to east)
    } else listOf(west to east)
}

enum class SatelliteRegionId { BRITISH_ISLES, EUROPE, NORTH_AMERICA_EAST, NORTH_AMERICA_WEST, LOCAL }

data class SatelliteRegion(
    val id: SatelliteRegionId,
    /** Place coordinates in this smaller footprint select the region. */
    val selectionBounds: SatelliteGeoBounds,
    /** Requests are limited to this footprint, including roughly 200 km of margin. */
    val requestBounds: SatelliteGeoBounds,
)

/** Place-owned regions. Camera movement never participates in this selection. */
object SatelliteRegionPolicy {
    private val fixed = listOf(
        SatelliteRegion(
            SatelliteRegionId.BRITISH_ISLES,
            SatelliteGeoBounds(-11.5, 49.0, 2.5, 61.5),
            SatelliteGeoBounds(-15.0, 47.2, 6.0, 63.3),
        ),
        SatelliteRegion(
            SatelliteRegionId.EUROPE,
            SatelliteGeoBounds(-12.0, 34.0, 40.0, 72.0),
            SatelliteGeoBounds(-16.0, 32.2, 44.0, 73.8),
        ),
        // The overlap is deliberate; East wins deterministically in the shared central strip.
        SatelliteRegion(
            SatelliteRegionId.NORTH_AMERICA_EAST,
            SatelliteGeoBounds(-105.0, 20.0, -50.0, 72.0),
            SatelliteGeoBounds(-109.0, 18.2, -46.0, 73.8),
        ),
        SatelliteRegion(
            SatelliteRegionId.NORTH_AMERICA_WEST,
            SatelliteGeoBounds(-170.0, 18.0, -95.0, 72.0),
            SatelliteGeoBounds(-174.0, 16.2, -91.0, 73.8),
        ),
    )

    fun select(place: SavedPlace): SatelliteRegion = fixed.firstOrNull {
        it.selectionBounds.contains(place.latitude, place.longitude)
    } ?: local(place)

    fun clip(metadata: EumetLayerMetadata, region: SatelliteRegion): EumetLayerMetadata? {
        val provider = SatelliteGeoBounds(metadata.west, metadata.south, metadata.east, metadata.north)
        val overlap = provider.intersections(region.requestBounds).singleOrNull() ?: return null
        return metadata.copy(
            west = overlap.west,
            south = overlap.south,
            east = overlap.east,
            north = overlap.north,
        )
    }

    private fun local(place: SavedPlace): SatelliteRegion {
        val latitude = place.latitude.coerceIn(-85.0, 85.0)
        val longitude = place.longitude.coerceIn(-180.0, 180.0)
        val longitudeScale = cos(Math.toRadians(latitude)).coerceAtLeast(0.18)
        val innerLat = 2.0
        val innerLon = (2.0 / longitudeScale).coerceAtMost(12.0)
        val marginLat = 1.8 // approximately 200 km
        val marginLon = (1.8 / longitudeScale).coerceAtMost(12.0)
        fun bounds(latRadius: Double, lonRadius: Double) = SatelliteGeoBounds(
            (longitude - lonRadius).coerceAtLeast(-180.0),
            (latitude - latRadius).coerceAtLeast(-85.0),
            (longitude + lonRadius).coerceAtMost(180.0),
            (latitude + latRadius).coerceAtMost(85.0),
        )
        return SatelliteRegion(
            SatelliteRegionId.LOCAL,
            bounds(innerLat, innerLon),
            bounds(innerLat + marginLat, innerLon + marginLon),
        )
    }
}

data class SatelliteCacheContext(
    val region: SatelliteRegion,
) {
    val identity: String = buildString {
        append(region.id.name).append(':')
        append(region.requestBounds.west).append(',').append(region.requestBounds.south).append(',')
        append(region.requestBounds.east).append(',').append(region.requestBounds.north)
    }
}

data class SatelliteRegionalImageRequest(
    val url: String,
    val bounds: SatelliteGeoBounds,
    val width: Int,
    val height: Int,
    val projectedBbox: String,
)

/** One exact, transparent, projected WMS image for the provider/region intersection. */
object SatelliteRegionalImagePolicy {
    const val maximumDimension = 1024
    const val minimumDimension = 128
    private const val EARTH_RADIUS_METERS = 6_378_137.0
    private const val MERCATOR_LATITUDE = 85.05112878
    private const val EUMET_SCHEME = "https"
    private const val EUMET_HOST = "view.eumetsat.int"
    private const val EUMET_PATH = "/geoserver/wms"

    fun request(metadata: EumetLayerMetadata): SatelliteRegionalImageRequest {
        val bounds = SatelliteGeoBounds(metadata.west, metadata.south, metadata.east, metadata.north)
        require(!bounds.crossesAntimeridian) { "Regional WMS images cannot cross the antimeridian" }
        fun x(longitude: Double) = EARTH_RADIUS_METERS * Math.toRadians(longitude)
        fun y(latitude: Double): Double {
            val radians = Math.toRadians(latitude.coerceIn(-MERCATOR_LATITUDE, MERCATOR_LATITUDE))
            return EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + radians / 2.0))
        }
        val west = x(bounds.west)
        val east = x(bounds.east)
        val south = y(bounds.south)
        val north = y(bounds.north)
        val projectedWidth = (east - west).coerceAtLeast(1.0)
        val projectedHeight = (north - south).coerceAtLeast(1.0)
        val aspect = projectedWidth / projectedHeight
        val width: Int
        val height: Int
        if (aspect >= 1.0) {
            width = maximumDimension
            height = (maximumDimension / aspect).roundToInt()
                .coerceIn(minimumDimension, maximumDimension)
        } else {
            height = maximumDimension
            width = (maximumDimension * aspect).roundToInt()
                .coerceIn(minimumDimension, maximumDimension)
        }
        val bbox = String.format(Locale.ROOT, "%.2f,%.2f,%.2f,%.2f", west, south, east, north)
        val url = metadata.tileUrl()
            .replace("{bbox-epsg-3857}", bbox)
            .replace("&width=256&height=256", "&width=$width&height=$height")
        requireAllowedRequest(url, metadata, bbox, width, height)
        return SatelliteRegionalImageRequest(url, bounds, width, height, bbox)
    }

    /** Pins app-owned frame acquisition to the exact EUMET endpoint/product/time/region. */
    internal fun requireAllowedRequest(
        url: String,
        metadata: EumetLayerMetadata,
        bbox: String,
        width: Int,
        height: Int,
    ) {
        val uri = URI(url)
        require(uri.scheme == EUMET_SCHEME && uri.host == EUMET_HOST)
        require(uri.port == -1 || uri.port == 443)
        require(uri.userInfo == null && uri.fragment == null && uri.path == EUMET_PATH)
        require(!url.contains('{') && !url.contains('}'))
        val parameters = uri.rawQuery.orEmpty().split('&').associate { entry ->
            val separator = entry.indexOf('=')
            val rawKey = if (separator >= 0) entry.substring(0, separator) else entry
            val rawValue = if (separator >= 0) entry.substring(separator + 1) else ""
            URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT) to
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
        }
        require(parameters["service"] == "WMS" && parameters["request"] == "GetMap")
        require(parameters["layers"] == metadata.layerName)
        require(parameters["time"] == java.time.Instant.ofEpochSecond(metadata.validEpochSeconds).toString())
        require(parameters["srs"] == "EPSG:3857" && parameters["bbox"] == bbox)
        require(parameters["width"] == width.toString() && parameters["height"] == height.toString())
        require(parameters["format"] == "image/png" && parameters["transparent"] == "true")
    }
}

/** Builds the finite observed frame window; forecast time repeats only the latest observation. */
object SatelliteFrameWindowPolicy {
    fun frames(
        catalog: Collection<EumetLayerMetadata>,
        weather: CurrentWeather?,
        place: SavedPlace,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): Map<RadarMapLayer, List<EumetLayerMetadata>> {
        if (endEpochSeconds < startEpochSeconds) return emptyMap()
        val lightningCatalog = catalog.firstOrNull { it.product == EumetProduct.LIGHTNING }
        val lightning = lightningCatalog?.let { framesFor(it, startEpochSeconds, endEpochSeconds) }.orEmpty()
        val cloudCatalog = catalog.filter { it.choice == RadarMapLayer.FOG }
        val cloudTimes = cloudCatalog.flatMap { framesFor(it, startEpochSeconds, endEpochSeconds) }
            .map(EumetLayerMetadata::validEpochSeconds)
            .plus(listOf(startEpochSeconds, endEpochSeconds))
            .distinct().sorted()
        val clouds = cloudTimes.mapNotNull { time ->
            SatelliteFrameSelectionPolicy.clouds(cloudCatalog, weather, place, time)
        }.distinctBy(EumetLayerMetadata::frameIdentity)
        return buildMap {
            if (clouds.isNotEmpty()) put(RadarMapLayer.FOG, clouds)
            if (lightning.isNotEmpty()) put(RadarMapLayer.LIGHTNING, lightning)
        }
    }

    private fun framesFor(
        metadata: EumetLayerMetadata,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
    ): List<EumetLayerMetadata> {
        if (endEpochSeconds < metadata.availableFromEpochSeconds) return emptyList()
        if (startEpochSeconds >= metadata.latestEpochSeconds) {
            return listOf(metadata.copy(validEpochSeconds = metadata.latestEpochSeconds))
        }
        val first = metadata.frameAtOrBefore(max(startEpochSeconds, metadata.availableFromEpochSeconds))
            ?: metadata.copy(validEpochSeconds = metadata.availableFromEpochSeconds)
        val last = min(endEpochSeconds, metadata.latestEpochSeconds)
        val result = mutableListOf(first)
        var time = first.validEpochSeconds + metadata.cadenceSeconds
        while (time <= last) {
            result += metadata.copy(validEpochSeconds = time)
            time += metadata.cadenceSeconds
        }
        if (endEpochSeconds >= metadata.latestEpochSeconds &&
            result.last().validEpochSeconds != metadata.latestEpochSeconds) {
            result += metadata.copy(validEpochSeconds = metadata.latestEpochSeconds)
        }
        return result.distinctBy(EumetLayerMetadata::frameIdentity)
    }
}

object SatelliteCacheIdentity {
    fun frame(metadata: EumetLayerMetadata, context: SatelliteCacheContext): String =
        "${metadata.frameIdentity}|${metadata.west},${metadata.south}," +
            "${metadata.east},${metadata.north}|${context.identity}"
}

object SatelliteRenderIdentity {
    fun frame(metadata: EumetLayerMetadata, context: SatelliteCacheContext): String =
        SatelliteCacheIdentity.frame(metadata, context)
}

data class SatellitePlanProgress(val ready: Int, val total: Int) {
    init { require(total >= 0 && ready in 0..total) }
    val complete: Boolean get() = total > 0 && ready == total
}

/** Every unique region-wide frame must be disk-ready before an overlay becomes visible. */
object SatelliteFullSetPolicy {
    fun uniqueFrames(
        frames: Collection<EumetLayerMetadata>,
        context: SatelliteCacheContext,
    ): List<EumetLayerMetadata> = frames.distinctBy { SatelliteCacheIdentity.frame(it, context) }

    fun planKey(
        frames: Collection<EumetLayerMetadata>,
        context: SatelliteCacheContext,
    ): String = uniqueFrames(frames, context)
        .joinToString(prefix = "${context.identity}|", separator = ";") {
            SatelliteCacheIdentity.frame(it, context)
        }

    fun progress(
        frames: Collection<EumetLayerMetadata>,
        context: SatelliteCacheContext,
        isReady: (String) -> Boolean,
    ): SatellitePlanProgress {
        val unique = uniqueFrames(frames, context)
        return SatellitePlanProgress(
            ready = unique.count { isReady(SatelliteCacheIdentity.frame(it, context)) },
            total = unique.size,
        )
    }

    fun visibleFrame(
        desired: EumetLayerMetadata?,
        progress: SatellitePlanProgress,
    ): EumetLayerMetadata? = desired?.takeIf { progress.complete }
}

/** A revealed replacement needs one later fully-rendered callback before its predecessor retires. */
object SatelliteHandoffPolicy {
    fun mayRetire(
        expectedGeneration: Long?,
        activeGeneration: Long?,
        revealedAtRenderSequence: Long?,
        currentRenderSequence: Long,
        fullyRendered: Boolean,
    ): Boolean = fullyRendered && expectedGeneration != null &&
        expectedGeneration == activeGeneration && revealedAtRenderSequence != null &&
        currentRenderSequence > revealedAtRenderSequence
}
