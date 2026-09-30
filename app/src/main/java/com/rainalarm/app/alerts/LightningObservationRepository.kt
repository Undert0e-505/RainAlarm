package com.rainalarm.app.alerts

import android.graphics.BitmapFactory
import com.rainalarm.app.data.EumetProduct
import com.rainalarm.app.data.EumetViewRepository
import com.rainalarm.app.data.SavedPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

enum class LightningEvaluationOutcome { DETECTED, NO_DETECTION, NO_NEW_FRAMES, UNAVAILABLE }

data class LightningFrameResult(
    val epochSeconds: Long,
    val observation: LightningFrameObservation,
)

data class LightningEvaluationResult(
    val outcome: LightningEvaluationOutcome,
    val frames: List<LightningFrameResult>,
    val lastContiguousEpochSeconds: Long?,
    val latestAdvertisedEpochSeconds: Long?,
    val radiusKilometres: Double,
    val evaluatedAtEpochSeconds: Long,
) {
    val observations: List<Pair<Long, LightningFrameObservation>>
        get() = frames.map { it.epochSeconds to it.observation }
}

/** Strict enough for untrusted WMS responses without retaining decoded observation imagery. */
object LightningPngValidator {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val maximumChunkBytes = 2 * 1024 * 1024

    data class Dimensions(val width: Int, val height: Int)

    fun validate(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int): Dimensions {
        require(bytes.size in 33..maximumChunkBytes) { "Lightning image size is invalid" }
        require(bytes.copyOfRange(0, 8).contentEquals(signature)) { "Lightning image is not PNG" }
        var offset = 8
        var dimensions: Dimensions? = null
        var sawData = false
        var sawEnd = false
        while (offset + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            require(length >= 0 && length <= maximumChunkBytes && offset + 12L + length <= bytes.size) {
                "Lightning PNG chunk is invalid"
            }
            val typeStart = offset + 4
            val dataStart = typeStart + 4
            val crcStart = dataStart + length
            val type = bytes.copyOfRange(typeStart, dataStart).toString(Charsets.US_ASCII)
            val crc = CRC32().apply { update(bytes, typeStart, 4 + length) }.value
            val expectedCrc = ByteBuffer.wrap(bytes, crcStart, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and
                0xffffffffL
            require(crc == expectedCrc) { "Lightning PNG checksum is invalid" }
            if (type == "IHDR") {
                require(dimensions == null && length == 13) { "Lightning PNG header is invalid" }
                val width = ByteBuffer.wrap(bytes, dataStart, 4).order(ByteOrder.BIG_ENDIAN).int
                val height = ByteBuffer.wrap(bytes, dataStart + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                dimensions = Dimensions(width, height)
            }
            if (type == "IDAT") sawData = true
            offset = crcStart + 4
            if (type == "IEND") {
                require(length == 0 && offset == bytes.size) { "Lightning PNG ending is invalid" }
                sawEnd = true
                break
            }
        }
        val result = requireNotNull(dimensions) { "Lightning PNG has no header" }
        require(sawData && sawEnd && result.width == expectedWidth && result.height == expectedHeight) {
            "Lightning PNG dimensions are invalid"
        }
        return result
    }
}

/**
 * Bounded target-centred acquisition for EUMETSAT's five-minute accumulated flash-area product.
 * It deliberately returns decisions and frame identities only; decoded imagery is never cached.
 */
class LightningObservationRepository(
    private val fetcher: suspend (String, Int) -> ByteArray = ::fetchHttps,
) {
    suspend fun evaluate(
        place: SavedPlace,
        policy: LightningDetectionPolicy = LightningDetectionPolicy(),
        lastContiguousEpochSeconds: Long? = null,
        forceMetadata: Boolean = false,
        nowEpochSeconds: Long = Instant.now().epochSecond,
    ): LightningEvaluationResult {
        val metadata = try {
            EumetViewRepository.advertisedMetadata(EumetProduct.LIGHTNING, forceMetadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return unavailable(policy, lastContiguousEpochSeconds, nowEpochSeconds)
        }
        if (!metadata.freshAt(nowEpochSeconds) || !GeoCirclePolicy.circleCoveredByEllipticalFootprint(
                place.latitude,
                place.longitude,
                policy.radiusKilometres,
                GeographicBounds(metadata.west, metadata.south, metadata.east, metadata.north),
            )
        ) return unavailable(
            policy,
            lastContiguousEpochSeconds,
            nowEpochSeconds,
            metadata.latestEpochSeconds,
        )

        val identities = runCatching {
            LightningFrameCatchUpPolicy.frames(
                metadata.availableFromEpochSeconds,
                metadata.latestEpochSeconds,
                metadata.cadenceSeconds,
                lastContiguousEpochSeconds,
            )
        }.getOrElse {
            return unavailable(
                policy,
                lastContiguousEpochSeconds,
                nowEpochSeconds,
                metadata.latestEpochSeconds,
            )
        }
        if (identities.isEmpty()) return LightningEvaluationResult(
            LightningEvaluationOutcome.NO_NEW_FRAMES,
            emptyList(),
            lastContiguousEpochSeconds,
            metadata.latestEpochSeconds,
            policy.radiusKilometres,
            nowEpochSeconds,
        )

        val bounds = requestBounds(place, policy)
        val coverage = GeographicBounds(metadata.west, metadata.south, metadata.east, metadata.north)
        val results = mutableListOf<LightningFrameResult>()
        for (identity in identities) {
            coroutineContext.ensureActive()
            val observation = try {
                val bytes = fetcher(wmsUrl(bounds, identity, policy.imagePixels), maximumImageBytes)
                LightningPngValidator.validate(bytes, policy.imagePixels, policy.imagePixels)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Lightning PNG could not be decoded")
                try {
                    require(bitmap.width == policy.imagePixels && bitmap.height == policy.imagePixels)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val alpha = ByteArray(pixels.size) { index -> (pixels[index] ushr 24).toByte() }
                    LightningPixelDetector.evaluate(
                        LightningPixelFrame(
                            bitmap.width,
                            bitmap.height,
                            alpha,
                            bounds,
                            coverage,
                            identity,
                        ),
                        place.latitude,
                        place.longitude,
                        policy,
                    )
                } finally {
                    bitmap.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LightningFrameObservation.UNAVAILABLE
            }
            results += LightningFrameResult(identity, observation)
        }

        var contiguous = lastContiguousEpochSeconds
        for (result in results) {
            if (result.observation == LightningFrameObservation.UNAVAILABLE) break
            contiguous = result.epochSeconds
        }
        val outcome = when {
            results.any { it.observation == LightningFrameObservation.DETECTED } ->
                LightningEvaluationOutcome.DETECTED
            results.all { it.observation == LightningFrameObservation.NO_DETECTION } ->
                LightningEvaluationOutcome.NO_DETECTION
            else -> LightningEvaluationOutcome.UNAVAILABLE
        }
        return LightningEvaluationResult(
            outcome,
            results,
            contiguous,
            metadata.latestEpochSeconds,
            policy.radiusKilometres,
            nowEpochSeconds,
        )
    }

    private fun requestBounds(place: SavedPlace, policy: LightningDetectionPolicy): GeographicBounds {
        val reach = policy.radiusKilometres + policy.samplingMarginKilometres
        val latitudeDelta = reach / 111.32
        val longitudeDelta = reach / (111.32 * cos(Math.toRadians(place.latitude)).coerceAtLeast(0.05))
        return GeographicBounds(
            GeoCirclePolicy.wrapLongitude(place.longitude - longitudeDelta),
            place.latitude - latitudeDelta,
            GeoCirclePolicy.wrapLongitude(place.longitude + longitudeDelta),
            place.latitude + latitudeDelta,
        )
    }

    private fun wmsUrl(bounds: GeographicBounds, frameEpochSeconds: Long, pixels: Int): String {
        // LI coverage does not cross the antimeridian, so a wrapped request would already have
        // failed the full-circle coverage test above.
        require(bounds.west <= bounds.east)
        val bbox = String.format(
            Locale.ROOT,
            "%.7f,%.7f,%.7f,%.7f",
            bounds.west,
            bounds.south,
            bounds.east,
            bounds.north,
        )
        return "https://$host/geoserver/wms?service=WMS&version=1.1.1&request=GetMap" +
            "&layers=${EumetProduct.LIGHTNING.layerName}" +
            "&styles=${EumetProduct.LIGHTNING.styleName}" +
            "&format=image%2Fpng&transparent=true&srs=EPSG%3A4326" +
            "&bbox=${URLEncoder.encode(bbox, "UTF-8")}&width=$pixels&height=$pixels" +
            "&time=${URLEncoder.encode(Instant.ofEpochSecond(frameEpochSeconds).toString(), "UTF-8")}"
    }

    private fun unavailable(
        policy: LightningDetectionPolicy,
        checkpoint: Long?,
        now: Long,
        latest: Long? = null,
    ) = LightningEvaluationResult(
        LightningEvaluationOutcome.UNAVAILABLE,
        emptyList(),
        checkpoint,
        latest,
        policy.radiusKilometres,
        now,
    )

    private companion object {
        const val host = "view.eumetsat.int"
        const val maximumImageBytes = 2 * 1024 * 1024

        suspend fun fetchHttps(rawUrl: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
            val url = URL(rawUrl)
            require(url.protocol == "https" && url.host == host)
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7_000
            connection.readTimeout = 12_000
            try {
                if (connection.responseCode != 200) throw IOException("Lightning HTTP response unavailable")
                if (connection.contentType?.lowercase(Locale.ROOT)?.contains("image/png") != true) {
                    throw IOException("Lightning response was not PNG")
                }
                if (connection.contentLengthLong !in -1L..limit.toLong()) {
                    throw IOException("Lightning response exceeded size limit")
                }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > limit) throw IOException("Lightning response exceeded size limit")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
