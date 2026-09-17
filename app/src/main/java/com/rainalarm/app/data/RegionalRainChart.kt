package com.rainalarm.app.data

import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** MeteoGroup's AREA_ID profile is location-selected but area-level, not a pin-pixel sample. */
data class RegionalRainChart(
    val areaId: String,
    val domainMin: Int,
    val domainMax: Int,
    val intervalSeconds: Int,
    val startEpochSeconds: Long,
    val createdEpochSeconds: Long,
    val averages: List<Int>,
    val minimums: List<Int>,
    val maximums: List<Int>,
)

object RegionalRainChartParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val utcDateTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    private val areaIdPattern = Regex("UWZ[A-Z0-9_-]{4,32}")

    fun lookupAreaId(body: String): String {
        require(body.length <= 16 * 1024) { "Area lookup response is too large" }
        val results = json.parseToJsonElement(body).jsonArray
        val area = results.map { it.jsonObject }.firstOrNull {
            it["AREA_TYPE"]?.jsonPrimitive?.content == "UWZ"
        } ?: error("No supported radar chart area")
        return area["AREA_ID"]?.jsonPrimitive?.content?.also {
            require(areaIdPattern.matches(it)) { "Invalid radar chart area ID" }
        } ?: error("Missing radar chart area ID")
    }

    fun parseChart(body: String, requestedAreaId: String): RegionalRainChart {
        require(body.length <= 128 * 1024) { "Rain chart response is too large" }
        val value = json.parseToJsonElement(body).jsonObject
        require(areaIdPattern.matches(requestedAreaId))
        require(value.string("uwzid") == requestedAreaId) { "Rain chart area does not match lookup" }
        val domainMin = value.integer("domainMin")
        val domainMax = value.integer("domainMax")
        require(domainMin >= 0 && domainMax > domainMin && domainMax <= 10_000) {
            "Invalid rain chart domain"
        }
        val intervalMs = value.integer("interval")
        require(intervalMs in 30_000..600_000 && intervalMs % 1_000 == 0) {
            "Unsupported rain chart interval"
        }
        // The provider's domain is the display scale, not a hard bound on raw
        // samples. Its own fixtures contain legitimate negative min/avg values.
        // Keep a generous finite abuse limit, then clamp at normalization.
        val domainSpan = domainMax - domainMin
        val rawLower = domainMin - 2 * domainSpan
        val rawUpper = domainMax + 2 * domainSpan
        fun levels(key: String): List<Int> = value[key]?.jsonArray?.map { element ->
            element.jsonPrimitive.content.toInt().also {
                require(it in rawLower..rawUpper) { "Rain chart $key is outside safe bounds" }
            }
        } ?: error("Missing rain chart $key")
        val averages = levels("avg")
        val minimums = levels("min")
        val maximums = levels("max")
        require(averages.size in 2..121 && minimums.size == averages.size && maximums.size == averages.size)
        require(averages.indices.all { minimums[it] <= averages[it] && averages[it] <= maximums[it] }) {
            "Rain chart bands are inconsistent"
        }
        return RegionalRainChart(
            requestedAreaId, domainMin, domainMax, intervalMs / 1_000,
            parseUtc(value.string("startdtg")), parseUtc(value.string("creationdtg")),
            averages, minimums, maximums,
        )
    }

    private fun parseUtc(value: String): Long = LocalDateTime.parse(value, utcDateTime)
        .toEpochSecond(ZoneOffset.UTC)
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content
        ?: error("Missing rain chart $key")
    private fun JsonObject.integer(key: String): Int = string(key).toInt()
}

object RegionalRainChartSeries {
    const val MAX_AGE_SECONDS = 10 * 60L

    fun build(chart: RegionalRainChart, nowEpochSeconds: Long, travelBearingDegrees: Double?): RainMinuteSeries {
        val age = nowEpochSeconds - chart.createdEpochSeconds
        require(age in -120L..MAX_AGE_SECONDS) { "Rain chart is stale or dated in the future" }
        val lastEpoch = chart.startEpochSeconds +
            (chart.averages.lastIndex * chart.intervalSeconds).toLong()
        require(nowEpochSeconds in chart.startEpochSeconds..lastEpoch) {
            "Rain chart does not cover current time"
        }
        val lastMinute = floor((lastEpoch - nowEpochSeconds) / 60.0).toInt().coerceIn(0, 60)
        val domain = (chart.domainMax - chart.domainMin).toFloat()
        fun interpolate(values: List<Int>, target: Long): Float {
            val position = (target - chart.startEpochSeconds).toDouble() / chart.intervalSeconds
            val left = floor(position).toInt().coerceIn(0, values.lastIndex)
            val right = (left + 1).coerceAtMost(values.lastIndex)
            val fraction = (position - left).toFloat().coerceIn(0f, 1f)
            val source = values[left] + (values[right] - values[left]) * fraction
            return ((source - chart.domainMin) / domain).coerceIn(0f, 1f)
        }
        val points = (0..lastMinute).map { minute ->
            val target = nowEpochSeconds + minute * 60L
            RainMinutePoint(
                minute,
                interpolate(chart.minimums, target),
                interpolate(chart.averages, target),
                interpolate(chart.maximums, target),
                target > chart.createdEpochSeconds,
            )
        }
        return RainMinuteSeries(
            nowEpochSeconds, points, "MeteoGroup area rain chart", 1.0,
            if (lastMinute == 60) RainMinuteAvailability.AVAILABLE else RainMinuteAvailability.PARTIAL,
            unavailableReason = if (lastMinute == 60) null else "Area forecast ends at +$lastMinute min",
            latestObservationEpochSeconds = chart.createdEpochSeconds,
            travelBearingDegrees = travelBearingDegrees,
            intensityEncoding = RadarIntensityEncoding.REGIONAL_AREA_CHART,
        )
    }
}

interface RegionalRainChartEndpoint {
    suspend fun lookup(place: SavedPlace): String
    suspend fun chart(areaId: String): String
}

class HttpRegionalRainChartEndpoint : RegionalRainChartEndpoint {
    override suspend fun lookup(place: SavedPlace): String = fetch(
        "https://feed.alertspro.meteogroup.com/AlertsPro/AlertsProPollService.php" +
            "?method=lookupCoord&lat=${place.latitude}&lon=${place.longitude}",
        "https", "feed.alertspro.meteogroup.com", 16 * 1024,
    )

    override suspend fun chart(areaId: String): String {
        require(Regex("UWZ[A-Z0-9_-]{4,32}").matches(areaId))
        // This legacy chart host presents an invalid HTTPS hostname. Android permits
        // cleartext ONLY for this exact host; never disable certificate checks.
        return fetch(
            "http://android.weatherpro.weatherservice.meteogroup.de/weatherpro/RainService.php" +
                "?method=getRainChart&areaID=$areaId",
            "http", "android.weatherpro.weatherservice.meteogroup.de", 128 * 1024,
        )
    }

    private suspend fun fetch(rawUrl: String, scheme: String, host: String, limit: Int): String =
        withContext(Dispatchers.IO) {
            val url = URL(rawUrl)
            require(url.protocol == scheme && url.host == host) { "Unexpected rain chart host" }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_500
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RainAlarm/0.2")
            try {
                if (connection.responseCode != 200) throw IOException("Rain chart HTTP ${connection.responseCode}")
                require(connection.contentLengthLong in -1..limit.toLong()) { "Rain chart response is too large" }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= limit) { "Rain chart response is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }
}

class RegionalAreaIdCache(private val ttlSeconds: Long = 24 * 60 * 60L) {
    private data class Entry(val areaId: String, val savedAt: Long)
    private val entries = LinkedHashMap<String, Entry>()

    // Exact stable coordinates avoid sharing an AREA_ID across adjacent warning
    // zones. Live fixes that materially move intentionally require a new lookup.
    private fun key(place: SavedPlace): String =
        "${place.latitude.toBits()}:${place.longitude.toBits()}"

    @Synchronized fun get(place: SavedPlace, nowEpochSeconds: Long): String? {
        val entry = entries[key(place)] ?: return null
        if (abs(nowEpochSeconds - entry.savedAt) > ttlSeconds) {
            entries.remove(key(place))
            return null
        }
        return entry.areaId
    }

    @Synchronized fun put(place: SavedPlace, areaId: String, nowEpochSeconds: Long) {
        val key = key(place)
        entries.remove(key)
        entries[key] = Entry(areaId, nowEpochSeconds)
        if (entries.size > 128) entries.remove(entries.keys.first())
    }

    companion object { val shared = RegionalAreaIdCache() }
}

/** Optional area profile with explicit, tested raster-point fallback. */
class RegionalRainChartService(
    private val endpoint: RegionalRainChartEndpoint = HttpRegionalRainChartEndpoint(),
    private val cache: RegionalAreaIdCache = RegionalAreaIdCache.shared,
) {
    suspend fun preferChart(
        place: SavedPlace,
        nowEpochSeconds: Long,
        rasterFallback: RainMinuteSeries,
    ): RainMinuteSeries = try {
        val areaId = cache.get(place, nowEpochSeconds) ?: RegionalRainChartParser
            .lookupAreaId(endpoint.lookup(place)).also { cache.put(place, it, nowEpochSeconds) }
        val chart = RegionalRainChartParser.parseChart(endpoint.chart(areaId), areaId)
        RegionalRainChartSeries.build(chart, nowEpochSeconds, rasterFallback.travelBearingDegrees)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rasterFallback
    }
}
