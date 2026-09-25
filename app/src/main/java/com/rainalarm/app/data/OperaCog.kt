package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.RadarResolutionTier
import com.rainalarm.app.domain.WebMercator
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.InflaterInputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

private const val OPERA_TILE_SIZE = 512
private const val OPERA_SAMPLES_PER_PIXEL = 2
private const val OPERA_BYTES_PER_SAMPLE = 4
private const val OPERA_FILL_LIMIT = -1_000_000f

data class OperaFrameKey(val validityEpochSeconds: Long, val key: String)

/** Deterministic 5-minute OPERA keys avoid expensive bucket listings on every phone refresh. */
object OperaFrameKeyPolicy {
    const val CADENCE_SECONDS = 5 * 60L
    const val PUBLICATION_LAG_SECONDS = 4 * 60L
    const val DEFAULT_FRAME_COUNT = 13
    const val MAX_LATEST_PROBES = 8
    private val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd/'OPERA/COMP/OPERA@'yyyyMMdd'T'HHmm'@0@DBZH.tiff'")
        .withZone(ZoneOffset.UTC)

    fun keyFor(epochSeconds: Long): OperaFrameKey = OperaFrameKey(
        epochSeconds,
        formatter.format(Instant.ofEpochSecond(epochSeconds)),
    )

    fun latestCandidates(now: Instant): List<OperaFrameKey> {
        val ready = now.epochSecond - PUBLICATION_LAG_SECONDS
        val rounded = ready - Math.floorMod(ready, CADENCE_SECONDS)
        return List(MAX_LATEST_PROBES) { keyFor(rounded - it * CADENCE_SECONDS) }
    }

    fun history(latestEpochSeconds: Long, count: Int): List<OperaFrameKey> {
        val safeCount = count.coerceIn(2, DEFAULT_FRAME_COUNT)
        return List(safeCount) { index ->
            keyFor(latestEpochSeconds - (safeCount - 1L - index) * CADENCE_SECONDS)
        }
    }
}

enum class OperaTiffEndian(val byteOrder: ByteOrder) {
    LITTLE(ByteOrder.LITTLE_ENDIAN), BIG(ByteOrder.BIG_ENDIAN),
}

data class OperaCogMetadata(
    val endian: OperaTiffEndian,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tileOffsets: LongArray,
    val tileByteCounts: LongArray,
    val pixelWidthMetres: Double,
    val pixelHeightMetres: Double,
    val tiePixelX: Double,
    val tiePixelY: Double,
    val tieEasting: Double,
    val tieNorthing: Double,
    val centreLatitude: Double,
    val centreLongitude: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
    val semiMajorAxis: Double,
    val inverseFlattening: Double,
    val noData: Float,
    val fileLength: Long,
) {
    val tileColumns: Int get() = (width + tileWidth - 1) / tileWidth
    val tileRows: Int get() = (height + tileHeight - 1) / tileHeight
    val semiMinorAxis: Double get() = semiMajorAxis * (1.0 - 1.0 / inverseFlattening)
    val projection: String get() = "+proj=laea +lat_0=$centreLatitude +lon_0=$centreLongitude " +
        // Proj4J 1.4.x ignores +rf when creating this LAEA CRS. Supplying the equivalent
        // tag-derived semi-minor axis is both exact and portable across Android/JVM builds.
        "+x_0=$falseEasting +y_0=$falseNorthing +a=$semiMajorAxis +b=$semiMinorAxis " +
        "+units=m +no_defs"

    init {
        require(width > 0 && height > 0 && tileWidth > 0 && tileHeight > 0)
        require(tileOffsets.size == tileColumns * tileRows)
        require(tileByteCounts.size == tileOffsets.size)
        require(pixelWidthMetres > 0.0 && pixelHeightMetres > 0.0)
        require(tileOffsets.indices.all { tileOffsets[it] >= 0L && tileByteCounts[it] > 0L &&
            tileOffsets[it] + tileByteCounts[it] <= fileLength })
    }
}

/** A strict reader for the small classic-TIFF subset published by OPERA. */
object OperaCogParser {
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_DOUBLE = 12

    fun parse(header: ByteArray, fileLength: Long): OperaCogMetadata {
        require(header.size >= 8) { "OPERA TIFF header is truncated" }
        val endian = when (header.copyOfRange(0, 2).decodeToString()) {
            "II" -> OperaTiffEndian.LITTLE
            "MM" -> OperaTiffEndian.BIG
            else -> error("OPERA resource is not TIFF")
        }
        val reader = Reader(header, endian.byteOrder)
        require(reader.u16(2) == 42) { "OPERA resource is not classic TIFF" }
        val ifdOffset = reader.u32(4).toIntChecked("IFD offset")
        val entryCount = reader.u16(ifdOffset)
        val tags = HashMap<Int, Entry>(entryCount)
        repeat(entryCount) { index ->
            val offset = ifdOffset + 2 + index * 12
            val entry = Entry(reader.u16(offset), reader.u16(offset + 2), reader.u32(offset + 4), offset + 8)
            tags[entry.tag] = entry
        }
        fun longs(tag: Int): LongArray = reader.longs(requireNotNull(tags[tag]) { "Missing OPERA TIFF tag $tag" })
        fun shorts(tag: Int): IntArray = reader.shorts(requireNotNull(tags[tag]) { "Missing OPERA TIFF tag $tag" })
        fun doubles(tag: Int): DoubleArray = reader.doubles(requireNotNull(tags[tag]) { "Missing OPERA TIFF tag $tag" })
        fun scalar(tag: Int): Long = when (val entry = requireNotNull(tags[tag]) { "Missing OPERA TIFF tag $tag" }) {
            else -> when (entry.type) {
                TYPE_SHORT -> reader.shorts(entry).single().toLong()
                TYPE_LONG -> reader.longs(entry).single()
                else -> error("OPERA TIFF tag $tag has an incompatible type")
            }
        }

        val width = scalar(256).toIntChecked("width")
        val height = scalar(257).toIntChecked("height")
        val bits = shorts(258)
        val compression = scalar(259)
        val samples = scalar(277)
        val planar = scalar(284)
        val predictor = tags[317]?.let(reader::shorts)?.singleOrNull() ?: 1
        val tileWidth = scalar(322).toIntChecked("tile width")
        val tileHeight = scalar(323).toIntChecked("tile height")
        val tileOffsets = longs(324)
        val tileCounts = longs(325)
        val sampleFormats = shorts(339)
        require(bits.contentEquals(intArrayOf(32, 32))) { "OPERA TIFF samples are not float32" }
        require(compression == 8L) { "OPERA TIFF compression is not Adobe Deflate" }
        require(samples == OPERA_SAMPLES_PER_PIXEL.toLong() && planar == 1L) {
            "OPERA TIFF samples are not two-band contiguous data"
        }
        require(predictor == 1) { "OPERA TIFF predictor is unsupported" }
        require(sampleFormats.contentEquals(intArrayOf(3, 3))) { "OPERA TIFF sample format is not IEEE float" }
        require(tileWidth == OPERA_TILE_SIZE && tileHeight == OPERA_TILE_SIZE) {
            "OPERA TIFF tile geometry changed unexpectedly"
        }

        val scale = doubles(33550)
        val tie = doubles(33922)
        require(scale.size >= 2 && tie.size >= 6) { "OPERA TIFF georeference is incomplete" }
        val geoDoubles = doubles(34736)
        val geoKeys = shorts(34735)
        val keyValues = parseGeoKeys(geoKeys, geoDoubles)
        require(keyValues.short(1024) == 1 && keyValues.short(1025) == 1) {
            "OPERA TIFF is not projected PixelIsArea data"
        }
        require(keyValues.short(3075) == 10 && keyValues.short(3076) == 9001) {
            "OPERA TIFF is not metre-based Lambert Azimuthal Equal Area"
        }
        val noData = reader.ascii(requireNotNull(tags[42113]) { "Missing OPERA no-data tag" })
            .trimEnd('\u0000').trim().toFloat()
        require(noData <= OPERA_FILL_LIMIT) { "OPERA no-data sentinel is unsafe" }
        return OperaCogMetadata(
            endian, width, height, tileWidth, tileHeight, tileOffsets, tileCounts,
            scale[0], scale[1], tie[0], tie[1], tie[3], tie[4],
            keyValues.double(3089), keyValues.double(3088),
            keyValues.double(3082), keyValues.double(3083),
            keyValues.double(2057), keyValues.double(2059), noData, fileLength,
        )
    }

    private data class Entry(val tag: Int, val type: Int, val count: Long, val valueOffset: Int)

    private class Reader(private val bytes: ByteArray, private val order: ByteOrder) {
        fun u16(offset: Int): Int = buffer(offset, 2).short.toInt() and 0xffff
        fun u32(offset: Int): Long = buffer(offset, 4).int.toLong() and 0xffffffffL
        private fun f64(offset: Int): Double = buffer(offset, 8).double
        private fun buffer(offset: Int, size: Int): ByteBuffer {
            require(offset >= 0 && size >= 0 && offset.toLong() + size <= bytes.size) {
                "OPERA TIFF metadata points outside the fetched header"
            }
            return ByteBuffer.wrap(bytes, offset, size).order(order)
        }
        private fun typeSize(type: Int): Int = when (type) {
            TYPE_BYTE, TYPE_ASCII -> 1
            TYPE_SHORT -> 2
            TYPE_LONG -> 4
            TYPE_DOUBLE -> 8
            else -> error("Unsupported OPERA TIFF metadata type $type")
        }
        private fun start(entry: Entry): Int {
            val bytesNeeded = Math.multiplyExact(entry.count, typeSize(entry.type).toLong())
            require(bytesNeeded <= Int.MAX_VALUE) { "OPERA TIFF metadata array is too large" }
            return if (bytesNeeded <= 4) entry.valueOffset else u32(entry.valueOffset).toIntChecked("tag offset")
        }
        fun shorts(entry: Entry): IntArray {
            require(entry.type == TYPE_SHORT && entry.count <= 4096)
            val start = start(entry)
            return IntArray(entry.count.toInt()) { u16(start + it * 2) }
        }
        fun longs(entry: Entry): LongArray {
            require(entry.type == TYPE_LONG && entry.count <= 4096)
            val start = start(entry)
            return LongArray(entry.count.toInt()) { u32(start + it * 4) }
        }
        fun doubles(entry: Entry): DoubleArray {
            require(entry.type == TYPE_DOUBLE && entry.count <= 4096)
            val start = start(entry)
            return DoubleArray(entry.count.toInt()) { f64(start + it * 8) }
        }
        fun ascii(entry: Entry): String {
            require(entry.type == TYPE_ASCII && entry.count <= 1024)
            val start = start(entry)
            buffer(start, entry.count.toInt())
            return bytes.copyOfRange(start, start + entry.count.toInt()).decodeToString()
        }
    }

    private class GeoKeys(private val values: Map<Int, Double>) {
        fun short(key: Int): Int = requireNotNull(values[key]) { "Missing OPERA GeoKey $key" }.toInt()
        fun double(key: Int): Double = requireNotNull(values[key]) { "Missing OPERA GeoKey $key" }
    }

    private fun parseGeoKeys(directory: IntArray, doubles: DoubleArray): GeoKeys {
        require(directory.size >= 4 && directory[0] == 1) { "Invalid OPERA GeoKey directory" }
        val count = directory[3]
        require(directory.size == 4 + count * 4) { "Invalid OPERA GeoKey directory length" }
        val values = HashMap<Int, Double>()
        repeat(count) { index ->
            val offset = 4 + index * 4
            val key = directory[offset]
            val location = directory[offset + 1]
            val itemCount = directory[offset + 2]
            val valueOffset = directory[offset + 3]
            if (itemCount == 1 && location == 0) values[key] = valueOffset.toDouble()
            else if (itemCount == 1 && location == 34736) {
                values[key] = doubles.getOrElse(valueOffset) { error("Invalid OPERA GeoKey value offset") }
            }
        }
        return GeoKeys(values)
    }
}

private fun Long.toIntChecked(label: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "OPERA TIFF $label is too large" }
    return toInt()
}

data class OperaSourcePoint(val x: Float, val y: Float)

/** Exact ellipsoidal LAEA transform recovered from each OPERA GeoTIFF. */
class OperaProjection(private val metadata: OperaCogMetadata) {
    private val transform = CRSFactory().let { factory ->
        val geographic = factory.createFromParameters("opera-wgs84", "+proj=longlat +datum=WGS84 +no_defs")
        val opera = factory.createFromParameters("opera-laea", metadata.projection)
        CoordinateTransformFactory().createTransform(geographic, opera)
    }

    fun sourcePixel(point: GeoPoint): OperaSourcePoint {
        val projected = ProjCoordinate()
        transform.transform(ProjCoordinate(point.longitude, point.latitude), projected)
        // PixelIsArea tiepoints describe the outer top-left edge; array indices address centres.
        return OperaSourcePoint(
            ((projected.x - metadata.tieEasting) / metadata.pixelWidthMetres + metadata.tiePixelX - 0.5).toFloat(),
            ((metadata.tieNorthing - projected.y) / metadata.pixelHeightMetres + metadata.tiePixelY - 0.5).toFloat(),
        )
    }
}

/**
 * Fixed CIRRUS composite domain published by OPERA: 3,800 × 4,400 native 1 km pixels in
 * ellipsoidal LAEA (55 N, 10 E). A small inward tolerance prevents provider flapping from
 * insignificant GPS jitter on the hard product edge.
 */
object OperaProductDomain {
    private const val WIDTH = 3_800
    private const val HEIGHT = 4_400
    private const val EDGE_TOLERANCE_PIXELS = 2f
    private val metadata = OperaCogMetadata(
        endian = OperaTiffEndian.LITTLE,
        width = WIDTH,
        height = HEIGHT,
        tileWidth = 512,
        tileHeight = 512,
        tileOffsets = LongArray(72) { it.toLong() },
        tileByteCounts = LongArray(72) { 1L },
        pixelWidthMetres = 1_000.0,
        pixelHeightMetres = 1_000.0,
        tiePixelX = 0.0,
        tiePixelY = 0.0,
        tieEasting = -500.0002714332659,
        tieNorthing = 499.9999123872258,
        centreLatitude = 55.0,
        centreLongitude = 10.0,
        falseEasting = 1_950_000.0,
        falseNorthing = -2_100_000.0,
        semiMajorAxis = 6_378_137.0,
        inverseFlattening = 298.257223563,
        noData = -9_999_000f,
        fileLength = 100L,
    )
    private val projection = OperaProjection(metadata)

    fun contains(point: GeoPoint): Boolean {
        if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return false
        val pixel = projection.sourcePixel(point)
        return pixel.x.isFinite() && pixel.y.isFinite() &&
            pixel.x in EDGE_TOLERANCE_PIXELS..(WIDTH - EDGE_TOLERANCE_PIXELS) &&
            pixel.y in EDGE_TOLERANCE_PIXELS..(HEIGHT - EDGE_TOLERANCE_PIXELS)
    }
}

data class OperaSamplingPlan(
    val tier: RadarResolutionTier,
    val bounds: GeoQuad,
    val width: Int,
    val height: Int,
    val sourceX: FloatArray,
    val sourceY: FloatArray,
    val requiredTiles: IntArray,
) {
    init {
        require(width > 0 && height > 0 && sourceX.size == width * height && sourceY.size == sourceX.size)
    }
}

object OperaSamplingPlanner {
    private const val EARTH_CIRCUMFERENCE_METRES = 40_075_016.68557849

    fun regional(metadata: OperaCogMetadata, area: RegionalRadarArea): OperaSamplingPlan =
        build(metadata, RadarResolutionTier.REGIONAL, area.bounds, area.rasterWidth, area.rasterHeight)

    fun regional(metadata: OperaCogMetadata, place: SavedPlace): OperaSamplingPlan {
        val area = RegionalRadarAreas.forPoint(place.latitude, place.longitude)
        if (area != null) return regional(metadata, area)
        val plan = RainViewerRasterPlanner.forTier(place, RadarResolutionTier.REGIONAL)
        return build(
            metadata,
            RadarResolutionTier.REGIONAL,
            plan.targetBounds,
            plan.outputWidth,
            plan.outputHeight,
        )
    }

    fun detail(metadata: OperaCogMetadata, place: SavedPlace): OperaSamplingPlan {
        // Keep the existing z7 geographic detail footprint, but do not upsample a native 1 km COG.
        val bounds = WebMercator.coordinateImageBounds(
            GeoPoint(place.latitude, place.longitude), RadarResolutionTier.DETAIL.zoom, 512,
        )
        val span = WebMercator.worldFractionSpan(bounds)
        val groundScale = cos(Math.toRadians(place.latitude)).coerceAtLeast(0.05)
        val width = (span.width * EARTH_CIRCUMFERENCE_METRES * groundScale /
            metadata.pixelWidthMetres).roundToInt().coerceIn(96, 512)
        val height = (span.height * EARTH_CIRCUMFERENCE_METRES * groundScale /
            metadata.pixelHeightMetres).roundToInt().coerceIn(96, 512)
        return build(metadata, RadarResolutionTier.DETAIL, bounds, width, height)
    }

    private fun build(
        metadata: OperaCogMetadata,
        tier: RadarResolutionTier,
        bounds: GeoQuad,
        width: Int,
        height: Int,
    ): OperaSamplingPlan {
        val projection = OperaProjection(metadata)
        val (left, top) = WebMercator.worldFraction(bounds.topLeft)
        val (right, bottom) = WebMercator.worldFraction(bounds.bottomRight)
        val sourceX = FloatArray(width * height)
        val sourceY = FloatArray(width * height)
        val tiles = linkedSetOf<Int>()
        for (y in 0 until height) {
            val worldY = top + (y + 0.5) / height * (bottom - top)
            for (x in 0 until width) {
                val point = WebMercator.pointAtWorldFraction(
                    left + (x + 0.5) / width * (right - left), worldY,
                )
                val source = projection.sourcePixel(point)
                val index = y * width + x
                sourceX[index] = source.x
                sourceY[index] = source.y
                val ix = floor(source.x.toDouble()).toInt()
                val iy = floor(source.y.toDouble()).toInt()
                for (sy in iy..iy + 1) for (sx in ix..ix + 1) {
                    if (sx in 0 until metadata.width && sy in 0 until metadata.height) {
                        tiles += sy / metadata.tileHeight * metadata.tileColumns + sx / metadata.tileWidth
                    }
                }
            }
        }
        return OperaSamplingPlan(tier, bounds, width, height, sourceX, sourceY, tiles.sorted().toIntArray())
    }
}

class OperaDecodedTile(
    val width: Int,
    val height: Int,
    private val interleavedSamples: ByteArray,
    private val endian: OperaTiffEndian,
) {
    /** Reads only DBZH (sample 0); OPERA quality sample 1 remains deliberately unused. */
    fun reflectivity(index: Int): Float {
        require(index in 0 until interleavedSamples.size / (OPERA_SAMPLES_PER_PIXEL * OPERA_BYTES_PER_SAMPLE))
        val offset = index * OPERA_SAMPLES_PER_PIXEL * OPERA_BYTES_PER_SAMPLE
        fun unsigned(position: Int): Int = interleavedSamples[position].toInt() and 0xff
        val bits = when (endian) {
            OperaTiffEndian.LITTLE -> unsigned(offset) or
                (unsigned(offset + 1) shl 8) or
                (unsigned(offset + 2) shl 16) or
                (unsigned(offset + 3) shl 24)
            OperaTiffEndian.BIG -> (unsigned(offset) shl 24) or
                (unsigned(offset + 1) shl 16) or
                (unsigned(offset + 2) shl 8) or
                unsigned(offset + 3)
        }
        return Float.fromBits(bits)
    }
}

object OperaTileDecoder {
    fun decode(compressed: ByteArray, metadata: OperaCogMetadata, tileIndex: Int): OperaDecodedTile {
        require(tileIndex in metadata.tileOffsets.indices)
        val expected = metadata.tileWidth * metadata.tileHeight * OPERA_SAMPLES_PER_PIXEL * OPERA_BYTES_PER_SAMPLE
        val inflated = try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                // The TIFF metadata gives the exact decoded size. Reading directly into that
                // allocation avoids ByteArrayOutputStream's second multi-megabyte copy, which is
                // significant when screen and Now consumers prepare the same observations.
                val output = ByteArray(expected)
                var offset = 0
                while (offset < output.size) {
                    val read = input.read(output, offset, output.size - offset)
                    if (read < 0) break
                    offset += read
                }
                require(offset == expected) { "OPERA tile has an unexpected decoded size" }
                require(input.read() < 0) { "OPERA tile expands beyond its declared size" }
                output
            }
        } catch (failure: Exception) {
            throw IOException("OPERA tile could not be inflated", failure)
        }
        val tileX = tileIndex % metadata.tileColumns
        val tileY = tileIndex / metadata.tileColumns
        return OperaDecodedTile(
            minOf(metadata.tileWidth, metadata.width - tileX * metadata.tileWidth),
            minOf(metadata.tileHeight, metadata.height - tileY * metadata.tileHeight),
            inflated,
            metadata.endian,
        )
    }

    fun isCovered(value: Float, noData: Float): Boolean = value.isNaN() ||
        (value.isFinite() && value > OPERA_FILL_LIMIT && value != noData)

    fun reflectivityOrDry(value: Float): Float = if (value.isNaN()) Float.NEGATIVE_INFINITY else value
}
