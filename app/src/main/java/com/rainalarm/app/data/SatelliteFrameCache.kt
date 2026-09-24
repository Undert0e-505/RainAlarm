package com.rainalarm.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class SatelliteFrameAssetRequest(
    val cacheIdentity: String,
    val url: String,
    val width: Int,
    val height: Int,
    val metadata: EumetLayerMetadata,
) {
    val diskKey: String = SatelliteFrameCacheKey.from(
        "$cacheIdentity|$url|${width}x$height",
    )
}

data class SatelliteCachedFrame(
    val request: SatelliteFrameAssetRequest,
    val file: File,
) {
    val metadata: EumetLayerMetadata get() = request.metadata
}

object SatelliteFrameAssetPolicy {
    fun request(
        metadata: EumetLayerMetadata,
        context: SatelliteCacheContext,
    ): SatelliteFrameAssetRequest {
        val image = SatelliteRegionalImagePolicy.request(metadata)
        return SatelliteFrameAssetRequest(
            cacheIdentity = SatelliteCacheIdentity.frame(metadata, context),
            url = image.url,
            width = image.width,
            height = image.height,
            metadata = metadata,
        )
    }
}

internal object SatelliteFrameCacheKey {
    fun from(canonicalIdentity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalIdentity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object SatellitePngPolicy {
    const val maximumBytes = 8 * 1024 * 1024
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun requireValid(bytes: ByteArray, expectedWidth: Int, expectedHeight: Int) {
        require(bytes.size in 33..maximumBytes) { "Satellite PNG size is invalid" }
        require(bytes.copyOfRange(0, 8).contentEquals(signature)) {
            "Satellite response is not a PNG"
        }
        var offset = 8
        var first = true
        var width: Int? = null
        var height: Int? = null
        var hasImageData = false
        var ended = false
        while (offset < bytes.size) {
            require(offset + 12 <= bytes.size) { "Satellite PNG is truncated" }
            val length = unsignedInt(bytes, offset)
            require(length <= maximumBytes.toLong()) { "Satellite PNG chunk is too large" }
            val dataLength = length.toInt()
            val chunkEnd = offset + 12L + length
            require(chunkEnd <= bytes.size) { "Satellite PNG is truncated" }
            val typeOffset = offset + 4
            val type = bytes.copyOfRange(typeOffset, typeOffset + 4).toString(Charsets.US_ASCII)
            val crc = CRC32().apply { update(bytes, typeOffset, 4 + dataLength) }.value
            val expectedCrc = unsignedInt(bytes, typeOffset + 4 + dataLength)
            require(crc == expectedCrc) { "Satellite PNG checksum is invalid" }
            if (first) {
                require(type == "IHDR" && dataLength == 13) { "Satellite PNG header is invalid" }
                width = unsignedInt(bytes, typeOffset + 4).toInt()
                height = unsignedInt(bytes, typeOffset + 8).toInt()
                first = false
            }
            if (type == "IDAT" && dataLength > 0) hasImageData = true
            offset = chunkEnd.toInt()
            if (type == "IEND") {
                require(dataLength == 0 && offset == bytes.size) { "Satellite PNG ending is invalid" }
                ended = true
                break
            }
        }
        require(ended && hasImageData) { "Satellite PNG has no complete image data" }
        require(width == expectedWidth && height == expectedHeight) {
            "Satellite PNG dimensions are unexpected"
        }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
}

/** Atomic compressed-frame LRU. Temporary and corrupt files are never reported as ready. */
class SatelliteDiskCache(
    private val directory: File,
    val maximumBytes: Long = 128L * 1024L * 1024L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maximumBytes > 0)
        require(directory.isDirectory || directory.mkdirs()) { "Satellite cache is unavailable" }
        cleanupTemporaryFiles()
        prune()
    }

    @Synchronized
    fun get(request: SatelliteFrameAssetRequest): File? {
        val target = target(request)
        if (!target.isFile) return null
        val valid = runCatching {
            require(target.length() in 33..SatellitePngPolicy.maximumBytes.toLong())
            SatellitePngPolicy.requireValid(target.readBytes(), request.width, request.height)
        }.isSuccess
        if (!valid) {
            target.delete()
            return null
        }
        target.setLastModified(clockMillis())
        return target
    }

    @Synchronized
    fun put(request: SatelliteFrameAssetRequest, bytes: ByteArray): File {
        SatellitePngPolicy.requireValid(bytes, request.width, request.height)
        require(bytes.size <= maximumBytes) { "Satellite frame exceeds cache budget" }
        get(request)?.let { return it }
        val target = target(request)
        val temporary = File(directory, ".${request.diskKey}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            require(temporary.renameTo(target)) { "Satellite cache write could not be committed" }
            target.setLastModified(clockMillis())
            prune(protected = target)
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    fun cleanupTemporaryFiles() {
        directory.listFiles().orEmpty().filter { it.name.contains(".tmp-") }.forEach(File::delete)
    }

    @Synchronized
    fun prune(protected: File? = null) {
        val entries = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "png" }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy(File::getName))
        var total = entries.sumOf(File::length)
        entries.forEach { entry ->
            if (total <= maximumBytes) return
            val length = entry.length()
            if (entry != protected && entry.delete()) total -= length
        }
    }

    @Synchronized
    fun sizeBytes(): Long = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "png" }.sumOf(File::length)

    @Synchronized
    fun invalidate(request: SatelliteFrameAssetRequest): Boolean = target(request).delete()

    private fun target(request: SatelliteFrameAssetRequest): File =
        File(directory, "${request.diskKey}.png")
}

data class SatelliteFrameResponse(
    val status: Int,
    val contentType: String?,
    val declaredLength: Long,
    val body: ByteArray,
)

fun interface SatelliteFrameTransport {
    suspend fun fetch(request: SatelliteFrameAssetRequest): SatelliteFrameResponse
}

internal object SatelliteFrameResponsePolicy {
    fun requireBody(response: SatelliteFrameResponse, request: SatelliteFrameAssetRequest): ByteArray {
        require(response.status == HttpURLConnection.HTTP_OK) {
            "Satellite image returned HTTP ${response.status}"
        }
        require(response.contentType?.substringBefore(';')?.trim()
            ?.equals("image/png", ignoreCase = true) == true) {
            "Satellite image returned unexpected content"
        }
        require(response.declaredLength in -1..SatellitePngPolicy.maximumBytes.toLong()) {
            "Satellite image response is too large"
        }
        SatellitePngPolicy.requireValid(response.body, request.width, request.height)
        return response.body
    }
}

class EumetSatelliteFrameTransport : SatelliteFrameTransport {
    override suspend fun fetch(request: SatelliteFrameAssetRequest): SatelliteFrameResponse =
        withContext(Dispatchers.IO) {
            // Rebuild/validate the canonical URL policy before every network request.
            val regional = SatelliteRegionalImagePolicy.request(request.metadata)
            require(regional.url == request.url && regional.width == request.width &&
                regional.height == request.height)
            val connection = URL(request.url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "image/png")
            connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
            try {
                val status = connection.responseCode
                val contentType = connection.contentType
                val declaredLength = connection.contentLengthLong
                if (status != HttpURLConnection.HTTP_OK ||
                    declaredLength > SatellitePngPolicy.maximumBytes) {
                    return@withContext SatelliteFrameResponse(
                        status, contentType, declaredLength, byteArrayOf(),
                    )
                }
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= SatellitePngPolicy.maximumBytes) {
                            "Satellite image response is too large"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                SatelliteFrameResponse(status, contentType, declaredLength, body)
            } finally {
                connection.disconnect()
            }
        }
}

class SatelliteFrameStore(
    private val cache: SatelliteDiskCache,
    private val transport: SatelliteFrameTransport = EumetSatelliteFrameTransport(),
    val concurrency: Int = preferredConcurrency,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init { require(concurrency in 1..maximumConcurrency) }
    private val keyLocks = ConcurrentHashMap<String, Mutex>()
    /** Shared across Clouds and Lightning preparations, so their combined I/O stays bounded. */
    private val downloadSlots = Semaphore(concurrency)

    suspend fun prepare(
        requests: Collection<SatelliteFrameAssetRequest>,
        onProgress: suspend (ready: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SatelliteCachedFrame> = coroutineScope {
        val unique = requests.distinctBy(SatelliteFrameAssetRequest::diskKey)
        if (unique.isEmpty()) return@coroutineScope emptyList()
        val results = ConcurrentHashMap<String, SatelliteCachedFrame>()
        val hits = withContext(dispatcher) {
            unique.mapNotNull { request ->
                cache.get(request)?.let { SatelliteCachedFrame(request, it) }
            }
        }
        hits.forEach { results[it.request.diskKey] = it }
        val ready = AtomicInteger(hits.size)
        onProgress(ready.get(), unique.size)
        unique.filterNot { results.containsKey(it.diskKey) }.map { request ->
            async(dispatcher) {
                downloadSlots.withPermit {
                    val cached = acquire(request)
                    results[request.diskKey] = cached
                    onProgress(ready.incrementAndGet(), unique.size)
                }
            }
        }.awaitAll()
        // The full-set gate means every compressed frame must coexist on disk. Revalidate after
        // all writes so LRU pressure or external cleanup cannot leave a plan containing dead files.
        withContext(dispatcher) {
            unique.map { request ->
                val file = cache.get(request)
                    ?: throw IOException("Satellite frame set exceeded or lost its cache budget")
                SatelliteCachedFrame(request, file)
            }
        }
    }

    fun invalidate(request: SatelliteFrameAssetRequest): Boolean = cache.invalidate(request)

    private suspend fun acquire(request: SatelliteFrameAssetRequest): SatelliteCachedFrame {
        val lock = keyLocks.computeIfAbsent(request.diskKey) { Mutex() }
        lock.lock()
        try {
            cache.get(request)?.let { return SatelliteCachedFrame(request, it) }
            var lastFailure: Throwable? = null
            repeat(maximumAttempts) {
                currentCoroutineContext().ensureActive()
                try {
                    val bytes = SatelliteFrameResponsePolicy.requireBody(
                        transport.fetch(request), request,
                    )
                    return SatelliteCachedFrame(request, cache.put(request, bytes))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    lastFailure = failure
                }
            }
            throw IOException("Satellite frame could not be prepared", lastFailure)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val preferredConcurrency = 2
        const val maximumConcurrency = 3
        const val maximumAttempts = 3
    }
}

/** One process-wide coordinator prevents overlapping Radar sessions duplicating exact downloads. */
object SatelliteFrameStoreProvider {
    @Volatile private var instance: SatelliteFrameStore? = null
    @Volatile private var directoryPath: String? = null

    fun get(directory: File): SatelliteFrameStore {
        val canonicalPath = directory.canonicalPath
        instance?.takeIf { directoryPath == canonicalPath }?.let { return it }
        return synchronized(this) {
            instance?.takeIf { directoryPath == canonicalPath } ?: SatelliteFrameStore(
                SatelliteDiskCache(directory),
            ).also {
                directoryPath = canonicalPath
                instance = it
            }
        }
    }
}
