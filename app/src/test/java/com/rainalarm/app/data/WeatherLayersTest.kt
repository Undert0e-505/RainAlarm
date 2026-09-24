package com.rainalarm.app.data

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.ParserConfigurationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherLayersTest {
    private val now = Instant.parse("2026-09-17T03:00:00Z").epochSecond
    private val place = SavedPlace("Cardiff", 51.4816, -3.1791)
    private fun item(lat: Double = 51.48, lon: Double = -3.18, valid: Long = now): String =
        """{"latitude":$lat,"longitude":$lon,
        "current_units":{"temperature_2m":"°C","relative_humidity_2m":"%","pressure_msl":"hPa","uv_index":"","is_day":"","wind_speed_10m":"km/h","wind_direction_10m":"°"},
        "current":{"time":$valid,"temperature_2m":14.4,"relative_humidity_2m":75,"pressure_msl":1013.8,"uv_index":0.2,"is_day":0,"wind_speed_10m":13.7,"wind_direction_10m":230}}"""

    private fun windTimelineItem(
        lat: Double,
        lon: Double,
        times: List<Long>,
        speeds: List<Double>,
        directions: List<Double>,
    ): String = """{"latitude":$lat,"longitude":$lon,
        "minutely_15_units":{"time":"unixtime","wind_speed_10m":"km/h","wind_direction_10m":"°"},
        "minutely_15":{"time":$times,"wind_speed_10m":$speeds,"wind_direction_10m":$directions}}"""

    @Test fun `point weather retains source time model units and compact metric values`() {
        val result = OpenMeteoCurrentCodec.parse(item(), 1, now).single()
        assertEquals(14.4, result.temperatureC!!, 0.01)
        assertEquals(1013.8, result.pressureHpa!!, 0.01)
        assertEquals(75, result.humidityPercent)
        assertEquals(0.2, result.uvIndex!!, 0.01)
        assertEquals(false, result.isDay)
        assertEquals(230.0, result.windFromDegrees!!, 0.01)
        assertTrue(result.freshAt(now + 1_800))
        assertFalse(result.freshAt(now + 3_601))
        assertFalse(result.daylightFreshAt(now + 1_801))
        assertTrue(OpenMeteoCurrentCodec.url(listOf(place.latitude to place.longitude)).contains("current=temperature_2m"))
    }

    @Test fun `multi coordinate wind grid covers visible viewport and is parsed in provider order`() {
        val viewport = WindViewport(51.1, -3.8, 51.9, -2.6)
        val coords = viewport.coordinates()
        assertEquals(25, coords.size)
        assertEquals(25, coords.toSet().size)
        listOf(-0.17, 0.25, 0.5, 0.75, 1.17).forEachIndexed { index, expected ->
            assertEquals(expected, WindGridSamplingPolicy.viewportFractions[index], 0.0000001)
        }
        val visible = coords.filter { (lat, lon) ->
            lat in viewport.south..viewport.north && lon in viewport.west..viewport.east
        }
        assertEquals(9, visible.size)
        assertEquals(51.3, coords[6].first, 0.0000001)
        assertEquals(51.5, coords[11].first, 0.0000001)
        assertEquals(51.7, coords[16].first, 0.0000001)
        assertEquals(-3.5, coords[6].second, 0.0000001)
        assertEquals(-3.2, coords[7].second, 0.0000001)
        assertEquals(-2.9, coords[8].second, 0.0000001)
        assertEquals(51.5, coords[12].first, 0.00001)
        assertEquals(-3.2, coords[12].second, 0.00001)
        assertTrue(coords.take(5).all { it.first < viewport.south })
        assertTrue(coords.takeLast(5).all { it.first > viewport.north })
        assertTrue(coords.filterIndexed { index, _ -> index % 5 == 0 }
            .all { it.second < viewport.west })
        assertTrue(coords.filterIndexed { index, _ -> index % 5 == 4 }
            .all { it.second > viewport.east })
        val body = "[" + coords.joinToString(",") { item(it.first, it.second) } + "]"
        val parsed = OpenMeteoCurrentCodec.parse(body, 25, now)
        assertEquals(25, parsed.size)
        assertEquals(coords[0].first, parsed[0].latitude, 0.00001)
        assertEquals(coords[24].second, parsed[24].longitude, 0.00001)
        val url = OpenMeteoCurrentCodec.url(coords)
        assertEquals(24, url.substringAfter("latitude=").substringBefore("&longitude=").count { it == ',' })
    }

    @Test fun `wind viewport key ignores tiny pan but changes on zoom out and wraps date line`() {
        val original = WindViewport(51.1, -3.8, 51.9, -2.6)
        assertEquals(original.requestKey(), WindViewport(51.101, -3.799, 51.901, -2.599).requestKey())
        assertFalse(original.requestKey() == WindViewport(49.0, -7.0, 54.0, 1.0).requestKey())
        val crossing = WindViewport(10.0, 170.0, 20.0, -170.0)
        assertEquals(20.0, crossing.longitudeSpan, 0.0001)
        assertEquals(25, crossing.coordinates().size)
        assertEquals(listOf(175.0, -180.0, -175.0),
            listOf(crossing.coordinates()[6].second, crossing.coordinates()[12].second,
                crossing.coordinates()[18].second))
        assertTrue(crossing.coordinates().all { it.first in -85.0..85.0 && it.second in -180.0..180.0 })
        val polar = WindViewport(84.0, -10.0, 89.0, 10.0)
        assertTrue(polar.coordinates().all { it.first in -85.0..85.0 && it.second in -180.0..180.0 })
    }

    @Test fun `high zoom wind grid retains nine interior visible arrows despite snapped model cells`() {
        val tight = WindViewport(51.70000, -0.50000, 51.70008, -0.49988)
        val coordinates = tight.coordinates()
        assertTrue(tight.latitudeSpan < 0.001)
        assertTrue(tight.longitudeSpan < 0.001)
        assertEquals(9, coordinates.count { (lat, lon) ->
            lat in tight.south..tight.north && lon in tight.west..tight.east
        })
        assertEquals(25, coordinates.toSet().size)
        assertFalse(tight.requestKey() == WindViewport(51.70000, -0.50000,
            51.7008, -0.4988).requestKey())
        val snapped = OpenMeteoCurrentCodec.parse("[" + coordinates.joinToString(",") {
            item(51.7, -0.5)
        } + "]", 25, now)
        val grid = WindGrid(tight.requestKey(), snapped, coordinates, now)
        assertEquals(1, grid.points.map { it.latitude to it.longitude }.toSet().size)
        assertEquals(25, grid.renderCoordinates().toSet().size)
        assertEquals(coordinates, grid.renderCoordinates())
    }

    @Test fun `bounded wind timeline is requested once and follows the radar cursor at provider cadence`() {
        val start = Instant.parse("2026-09-17T01:03:00Z").epochSecond
        val end = Instant.parse("2026-09-17T02:37:00Z").epochSecond
        val window = WindTimelinePolicy.requestWindow(start, end)
        assertEquals(Instant.parse("2026-09-17T01:00:00Z").epochSecond, window.startEpochSeconds)
        assertEquals(Instant.parse("2026-09-17T02:45:00Z").epochSecond, window.endEpochSeconds)
        val url = OpenMeteoWindTimelineCodec.url(listOf(51.48 to -3.18, 52.0 to -2.0), window)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("minutely_15=wind_speed_10m,wind_direction_10m"))
        assertTrue(url.contains("start_minutely_15=2026-09-17T01:00"))
        assertTrue(url.contains("end_minutely_15=2026-09-17T02:45"))

        val times = listOf(window.startEpochSeconds, window.startEpochSeconds + 900,
            window.startEpochSeconds + 1_800)
        val body = "[" + listOf(
            windTimelineItem(51.48, -3.18, times, listOf(10.0, 11.0, 12.0),
                listOf(180.0, 190.0, 200.0)),
            windTimelineItem(52.0, -2.0, times, listOf(20.0, 21.0, 22.0),
                listOf(270.0, 280.0, 290.0)),
        ).joinToString(",") + "]"
        val frames = OpenMeteoWindTimelineCodec.parse(body, 2, now)
        assertEquals(times, frames.map(WindGridFrame::validEpochSeconds))
        assertEquals(listOf(10.0, 20.0), frames[0].points.map { it.windSpeedKmh })
        assertEquals(listOf(200.0, 290.0), frames[2].points.map { it.windFromDegrees })
        assertEquals(frames.first(), WindTimelinePolicy.frameAtOrBefore(frames, times.first() - 1))
        assertEquals(frames[1], WindTimelinePolicy.frameAtOrBefore(frames, times[1] + 899))
        assertEquals(frames.last(), WindTimelinePolicy.frameAtOrBefore(frames, times.last() + 3_600))

        val positions = listOf(51.48 to -3.18, 52.0 to -2.0)
        val grid = WindGrid("viewport", frames.first().points, positions, now, frames)
        assertEquals(listOf(10.0, 20.0), grid.displayedAt(times.first()).points.map { it.windSpeedKmh })
        assertEquals(listOf(11.0, 21.0), grid.displayedAt(times[1] + 10).points.map { it.windSpeedKmh })
        assertEquals(positions, grid.displayedAt(times.last()).renderCoordinates())
    }

    @Test fun `wind timeline bounds and malformed provider series fail safely`() {
        val window = WindTimelinePolicy.requestWindow(1_000L, 100_000L)
        assertTrue(window.endEpochSeconds - window.startEpochSeconds <= 8 * 3_600L)
        assertThrows(IllegalArgumentException::class.java) {
            WindTimelinePolicy.requestWindow(2_000L, 1_000L)
        }
        val times = listOf(1_800L, 2_700L, 3_700L)
        val malformedCadence = windTimelineItem(51.48, -3.18, times,
            listOf(10.0, 11.0, 12.0), listOf(180.0, 190.0, 200.0))
        assertThrows(IllegalArgumentException::class.java) {
            OpenMeteoWindTimelineCodec.parse(malformedCadence, 1, now)
        }
        val malformedCoordinates = windTimelineItem(95.0, -3.18,
            listOf(1_800L, 2_700L), listOf(10.0, 11.0), listOf(180.0, 190.0))
        assertThrows(IllegalArgumentException::class.java) {
            OpenMeteoWindTimelineCodec.parse(malformedCoordinates, 1, now)
        }
    }

    @Test fun `fast transient wind failure retries quickly then later attempts remain bounded`() =
        runBlocking {
            assertEquals(15_000, WindGridNetworkPolicy.connectTimeoutMillis)
            assertEquals(30_000, WindGridNetworkPolicy.readTimeoutMillis)
            assertEquals(55_000L, WindGridNetworkPolicy.totalTimeoutMillis)
            assertEquals(listOf(0L, 1_500L, 10_000L, 35_000L),
                WindGridNetworkPolicy.attemptOffsetsMillis)
            assertEquals(4, WindGridNetworkPolicy.maximumAttempts)

            val attempts = mutableListOf<Int>()
            val pauses = mutableListOf<Long>()
            var elapsed = 0L
            val result = WindGridNetworkPolicy.execute(
                request = { attempt ->
                    attempts += attempt
                    if (attempt == 1) throw IOException("temporary mobile failure")
                    "ready"
                },
                pause = { pauses += it; elapsed += it },
                elapsedRealtimeMillis = { elapsed },
            )
            assertEquals("ready", result)
            assertEquals(listOf(1, 2), attempts)
            assertEquals(listOf(1_500L), pauses)
            assertEquals(1_500L, elapsed)

            attempts.clear()
            pauses.clear()
            elapsed = 0L
            val later = WindGridNetworkPolicy.execute(
                request = { attempt ->
                    attempts += attempt
                    if (attempt <= 2) throw IOException("temporary mobile failure")
                    "ready"
                },
                pause = { pauses += it; elapsed += it },
                elapsedRealtimeMillis = { elapsed },
            )
            assertEquals("ready", later)
            assertEquals(listOf(1, 2, 3), attempts)
            assertEquals(listOf(1_500L, 8_500L), pauses)
            assertEquals(10_000L, elapsed)
        }

    @Test fun `wind retry honors bounded delta-seconds Retry-After`() = runBlocking {
        var elapsed = 0L
        val pauses = mutableListOf<Long>()
        val result = WindGridNetworkPolicy.execute(
            request = { attempt ->
                if (attempt == 1) throw WindGridHttpException(429, retryAfterMillis = 4_000L)
                "ready"
            },
            pause = { pauses += it; elapsed += it },
            elapsedRealtimeMillis = { elapsed },
        )
        assertEquals("ready", result)
        assertEquals(listOf(4_000L), pauses)
        assertEquals(4_000L, elapsed)
        assertEquals(5_000L, WindGridNetworkPolicy.retryAfterMillis("5"))
        assertEquals(WindGridNetworkPolicy.totalTimeoutMillis,
            WindGridNetworkPolicy.retryAfterMillis("999"))
        assertEquals(null, WindGridNetworkPolicy.retryAfterMillis("not-a-delay"))
    }

    @Test fun `wind cache-aside policy never holds cache lock across acquisition`() = runBlocking {
        val mutex = kotlinx.coroutines.sync.Mutex()
        val events = mutableListOf<String>()
        val result = WindGridCachePolicy.load(
            read = { mutex.withLock { events += "read"; null } },
            acquire = {
                // This would deadlock if the cache policy retained the same mutex around network IO.
                mutex.withLock { events += "network" }
                "fresh"
            },
            write = { value -> mutex.withLock { events += "write:$value" } },
        )
        assertEquals("fresh", result)
        assertEquals(listOf("read", "network", "write:fresh"), events)
    }

    @Test fun `wind grid request exhausts once and never retries cancellation or permanent response`() =
        runBlocking {
            var transientAttempts = 0
            var elapsed = 0L
            val exhausted = assertThrows(WindGridAttemptsExhaustedException::class.java) {
                runBlocking {
                    WindGridNetworkPolicy.execute(
                        request = {
                            transientAttempts++
                            throw IOException("still offline")
                        },
                        pause = { elapsed += it },
                        elapsedRealtimeMillis = { elapsed },
                    )
                }
            }
            assertEquals(WindGridNetworkPolicy.maximumAttempts, transientAttempts)
            assertEquals(35_000L, elapsed)
            assertEquals(WindGridFailureDiagnostic("network_io", retriesExhausted = true),
                WindGridFailureDiagnostics.classify(exhausted))

            var permanentAttempts = 0
            assertThrows(WindGridHttpException::class.java) {
                runBlocking {
                    WindGridNetworkPolicy.execute(
                        request = {
                            permanentAttempts++
                            throw WindGridHttpException(400)
                        },
                        pause = {},
                    )
                }
            }
            assertEquals(1, permanentAttempts)

            var cancelledAttempts = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    WindGridNetworkPolicy.execute(
                        request = {
                            cancelledAttempts++
                            throw CancellationException("obsolete viewport")
                        },
                        pause = {},
                    )
                }
            }
            assertEquals(1, cancelledAttempts)
            assertTrue(WindGridNetworkPolicy.isTransient(WindGridHttpException(503)))
            assertFalse(WindGridNetworkPolicy.isTransient(WindGridHttpException(404)))
            assertEquals(WindGridFailureDiagnostic("http", 503),
                WindGridFailureDiagnostics.classify(WindGridHttpException(503)))
            assertEquals(WindGridFailureDiagnostic("timeout"),
                WindGridFailureDiagnostics.classify(WindGridRequestTimeoutException()))
            assertEquals(WindGridFailureDiagnostic("parse_validation"),
                WindGridFailureDiagnostics.classify(IllegalArgumentException("invalid payload")))
            assertEquals(WindGridFailureDiagnostic("cancellation"),
                WindGridFailureDiagnostics.classify(CancellationException("superseded")))
        }

    @Test fun `selected place daily solar times use IANA zone and roll at local midnight`() {
        val zone = ZoneId.of("Europe/London")
        val sunrise = ZonedDateTime.of(2026, 9, 17, 6, 42, 0, 0, zone).toEpochSecond()
        val sunset = ZonedDateTime.of(2026, 9, 17, 19, 8, 0, 0, zone).toEpochSecond()
        val noon = ZonedDateTime.of(2026, 9, 17, 12, 0, 0, 0, zone).toEpochSecond()
        val body = item(valid = noon).replace("\"current\":",
            "\"timezone\":\"Europe/London\",\"daily\":{\"sunrise\":[$sunrise],\"sunset\":[$sunset]},\"current\":")
        val value = OpenMeteoCurrentCodec.parse(body, 1, noon).single()
        assertEquals("06:42" to "19:08", value.solarToday(noon))
        assertEquals(null, value.solarToday(noon + 86_400))
        assertTrue(OpenMeteoCurrentCodec.url(listOf(place.latitude to place.longitude), true)
            .contains("daily=sunrise,sunset&forecast_days=2"))
        assertTrue(OpenMeteoCurrentCodec.url(listOf(place.latitude to place.longitude), true)
            .contains("timezone=auto"))
    }

    @Test fun `solar formatting follows DST and missing daily fields are unavailable without losing weather`() {
        val zone = ZoneId.of("Europe/London")
        val spring = ZonedDateTime.of(2026, 3, 29, 6, 40, 0, 0, zone).toEpochSecond()
        val evening = ZonedDateTime.of(2026, 3, 29, 19, 24, 0, 0, zone).toEpochSecond()
        val noon = ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, zone).toEpochSecond()
        val body = item(valid = noon).replace("\"current\":",
            "\"timezone\":\"Europe/London\",\"daily\":{\"sunrise\":[$spring],\"sunset\":[$evening]},\"current\":")
        assertEquals("06:40" to "19:24", OpenMeteoCurrentCodec.parse(body, 1, noon).single().solarToday(noon))
        assertEquals(null, OpenMeteoCurrentCodec.parse(item(), 1, now).single().solarToday(now))
    }

    @Test fun `wrong units and stale data do not look current`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenMeteoCurrentCodec.parse(item().replace("km/h", "mph"), 1, now)
        }
        val stale = OpenMeteoCurrentCodec.parse(item(valid = now - 3_601), 1, now).single()
        assertFalse(stale.freshAt(now))
    }
}

class EumetLayersTest {
    private val now = Instant.parse("2026-09-17T03:00:00Z").epochSecond
    private val place = SavedPlace("Cardiff", 51.4816, -3.1791)
    private fun xml(lightningTime: String = "2026-09-17T02:45:00.000Z",
        cloudTypeTime: String = "2026-09-17T02:40:00.000Z",
        fogTime: String = "2026-09-17T02:30:00.000Z") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms"><Capability><Layer>
          <Layer><Name>mtg_fd:li_afa</Name><EX_GeographicBoundingBox>
          <westBoundLongitude>-70</westBoundLongitude><eastBoundLongitude>70</eastBoundLongitude>
          <southBoundLatitude>-70</southBoundLatitude><northBoundLatitude>70</northBoundLatitude>
          </EX_GeographicBoundingBox><Dimension name="time" default="$lightningTime" units="ISO8601" nearestValue="1">
          2025-05-30T15:00:00.000Z/$lightningTime/PT5M</Dimension></Layer>
          <Layer><Name>mtg_fd:rgb_cloudtype</Name><EX_GeographicBoundingBox>
          <westBoundLongitude>-81.27779388427734</westBoundLongitude>
          <eastBoundLongitude>81.28072357177734</eastBoundLongitude>
          <southBoundLatitude>-77.35063934326172</southBoundLatitude>
          <northBoundLatitude>77.35639190673828</northBoundLatitude>
          </EX_GeographicBoundingBox><Dimension name="time" default="$cloudTypeTime" units="ISO8601" nearestValue="1">
          2025-06-06T18:40:00.000Z/$cloudTypeTime/PT10M</Dimension></Layer>
          <Layer><Name>mtg_fd:rgb_fog</Name><EX_GeographicBoundingBox>
          <westBoundLongitude>-81.27779388427734</westBoundLongitude>
          <eastBoundLongitude>81.28072357177734</eastBoundLongitude>
          <southBoundLatitude>-77.35063934326172</southBoundLatitude>
          <northBoundLatitude>77.35639190673828</northBoundLatitude>
          </EX_GeographicBoundingBox><Dimension name="time" default="$fogTime" units="ISO8601" nearestValue="1">
          2025-06-06T18:40:00.000Z/$fogTime/PT10M</Dimension></Layer>
        </Layer></Capability></WMS_Capabilities>""".trimIndent().toByteArray()

    @Test fun `capabilities select exact layer valid time and coverage`() {
        val flashes = EumetCapabilities.parse(xml(), EumetProduct.LIGHTNING)
        val cloudType = EumetCapabilities.parse(xml(), EumetProduct.CLOUD_TYPE)
        val fog = EumetCapabilities.parse(xml(), EumetProduct.FOG_LOW_CLOUD)
        assertEquals(Instant.parse("2026-09-17T02:45:00Z").epochSecond, flashes.validEpochSeconds)
        assertEquals(Instant.parse("2026-09-17T02:40:00Z").epochSecond, cloudType.validEpochSeconds)
        assertEquals(EumetProduct.CLOUD_TYPE, cloudType.product)
        assertEquals("mtg_fd:rgb_cloudtype", cloudType.layerName)
        assertEquals(600L, cloudType.cadenceSeconds)
        assertEquals(Instant.parse("2025-06-06T18:40:00Z").epochSecond,
            cloudType.availableFromEpochSeconds)
        assertEquals(cloudType.validEpochSeconds, cloudType.latestEpochSeconds)
        assertEquals(EumetProduct.FOG_LOW_CLOUD, fog.product)
        assertEquals("mtg_fd:rgb_fog", fog.layerName)
        assertEquals(300L, flashes.cadenceSeconds)
        assertTrue(flashes.freshAt(now))
        assertTrue(cloudType.freshAt(now))
        assertTrue(fog.freshAt(now))
        assertTrue(flashes.covers(place))
        assertFalse(flashes.covers(SavedPlace("Tokyo", 35.6, 139.7)))
        assertFalse(flashes.freshAt(now + 901))
        assertFalse(fog.freshAt(now + 1_801))
    }

    @Test fun `satellite cursor selection floors to advertised cadence and never uses a future observation`() {
        val clouds = EumetLayerMetadata(
            RadarMapLayer.FOG, 2_800L, -20.0, 30.0, 20.0, 70.0,
            EumetProduct.CLOUD_TYPE, 1_000L, 2_800L, 600L,
        )
        assertEquals(null, clouds.frameAtOrBefore(999L))
        assertEquals(1_000L, clouds.frameAtOrBefore(1_000L)?.validEpochSeconds)
        assertEquals(1_000L, clouds.frameAtOrBefore(1_599L)?.validEpochSeconds)
        assertEquals(1_600L, clouds.frameAtOrBefore(1_600L)?.validEpochSeconds)
        assertEquals(2_200L, clouds.frameAtOrBefore(2_799L)?.validEpochSeconds)
        assertEquals(2_800L, clouds.frameAtOrBefore(2_800L)?.validEpochSeconds)
        assertEquals(2_800L, clouds.frameAtOrBefore(20_000L)?.validEpochSeconds)
        assertTrue(requireNotNull(clouds.frameAtOrBefore(2_799L)).validEpochSeconds <= 2_799L)
        assertEquals("CLOUD_TYPE:2200", clouds.frameAtOrBefore(2_799L)?.frameIdentity)

        val lightning = EumetLayerMetadata(
            RadarMapLayer.LIGHTNING, 1_900L, -20.0, 30.0, 20.0, 70.0,
            EumetProduct.LIGHTNING, 1_000L, 1_900L, 300L,
        )
        assertEquals(1_300L, SatelliteFrameSelectionPolicy.lightning(
            listOf(lightning), 1_599L)?.validEpochSeconds)
        assertEquals(1_900L, SatelliteFrameSelectionPolicy.lightning(
            listOf(lightning), 9_999L)?.validEpochSeconds)
    }

    @Test fun `cloud product follows daylight at the effective observation and holds latest in forecast`() {
        val midnight = Instant.parse("2026-09-17T00:00:00Z").epochSecond
        val noon = Instant.parse("2026-09-17T12:00:00Z").epochSecond
        val end = Instant.parse("2026-09-17T18:00:00Z").epochSecond
        fun metadata(product: EumetProduct) = EumetLayerMetadata(
            RadarMapLayer.FOG, end, -20.0, 30.0, 20.0, 70.0,
            product, midnight, end, 600L,
        )
        val catalog = listOf(metadata(EumetProduct.CLOUD_TYPE),
            metadata(EumetProduct.FOG_LOW_CLOUD))
        assertEquals(EumetProduct.FOG_LOW_CLOUD,
            SatelliteFrameSelectionPolicy.clouds(catalog, null, place, midnight)?.product)
        assertEquals(EumetProduct.CLOUD_TYPE,
            SatelliteFrameSelectionPolicy.clouds(catalog, null, place, noon)?.product)
        val forecast = SatelliteFrameSelectionPolicy.clouds(catalog, null, place, end + 86_400)
        assertEquals(end, forecast?.validEpochSeconds)
        assertEquals(EumetProduct.CLOUD_TYPE, forecast?.product)
        assertEquals(null, SatelliteFrameSelectionPolicy.clouds(catalog, null, place, midnight - 1))
    }

    @Test fun `satellite time dimensions reject malformed cadence and unsafe ranges`() {
        assertEquals(600L, EumetTimeDimensionCodec.parse(
            "2026-09-17T00:00:00Z/2026-09-17T01:00:00Z/PT10M").cadenceSeconds)
        listOf(
            "2026-09-17T00:00:00Z/2026-09-17T01:00:00Z/PT0S",
            "2026-09-17T01:00:00Z/2026-09-17T00:00:00Z/PT10M",
            "2026-09-17T00:00:00Z/2026-09-17T01:00:00Z/not-a-duration",
            "2026-09-17T00:00:00Z/2026-09-17T01:00:00Z/PT30.5S",
        ).forEach { value ->
            assertThrows(RuntimeException::class.java) { EumetTimeDimensionCodec.parse(value) }
        }
    }

    @Test fun `unsupported Android factory features do not abort mandatory XML safeguards`() {
        var attempted = 0
        EumetXmlSecurity.applyOptionalFeatures { _, _ ->
            attempted++
            throw ParserConfigurationException("unsupported on Android")
        }
        assertTrue(attempted >= 3)
        assertEquals("<root/>", EumetXmlSecurity.validatedUtf8("<root/>".toByteArray()))
    }

    @Test fun `capabilities reject document types entities and malformed UTF8 before DOM parsing`() {
        val externalEntity = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE WMS_Capabilities [<!ENTITY secret SYSTEM "file:///data/data/com.rainalarm.app/files/private">]>
            <WMS_Capabilities><Capability><Layer><Name>&secret;</Name></Layer></Capability></WMS_Capabilities>
        """.trimIndent().toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            EumetCapabilities.parse(externalEntity, RadarMapLayer.LIGHTNING)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EumetXmlSecurity.validatedUtf8(byteArrayOf(0x3c, 0xC3.toByte(), 0x28, 0x3e))
        }
    }

    @Test fun `pinned WMS frame and probe URLs remain HTTPS and spatial`() {
        val metadata = EumetCapabilities.parse(xml(), EumetProduct.LIGHTNING)
        val tile = metadata.tileUrl()
        assertTrue(tile.startsWith("https://view.eumetsat.int/geoserver/wms?"))
        assertTrue(tile.contains("{bbox-epsg-3857}"))
        assertTrue(tile.contains("mtg_fd%3Ali_afa"))
        assertTrue(tile.contains("time=2026-09-17T02%3A45%3A00Z"))
        val renderer = listOf(File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")).first(File::isFile).readText()
        assertTrue(renderer.contains("SatelliteRegionalImagePolicy.request(metadata)"))
        assertTrue(renderer.contains("ImageSource(sourceId, quad, bitmap)"))
        assertTrue(renderer.contains("SatelliteFrameAssetPolicy.request(it, satelliteCacheContext)"))
        assertFalse(renderer.contains("ImageSource(sourceId, quad, URI(request.url))"))
        assertFalse(renderer.contains("RasterSource(sourceId"))
        val probe = metadata.probeUrl(place)
        assertFalse(probe.contains("{bbox-epsg-3857}"))
        assertTrue(probe.contains("&width=64&height=64"))

        val cloudType = EumetCapabilities.parse(xml(), EumetProduct.CLOUD_TYPE)
        val fog = EumetCapabilities.parse(xml(), EumetProduct.FOG_LOW_CLOUD)
        assertTrue(cloudType.tileUrl().contains("layers=mtg_fd%3Argb_cloudtype"))
        assertTrue(cloudType.tileUrl().contains("time=2026-09-17T02%3A40%3A00Z"))
        assertTrue(fog.tileUrl().contains("layers=mtg_fd%3Argb_fog"))
        assertTrue(fog.tileUrl().contains("time=2026-09-17T02%3A30%3A00Z"))
        assertFalse(EumetCacheIdentity.metadata(EumetProduct.CLOUD_TYPE) ==
            EumetCacheIdentity.metadata(EumetProduct.FOG_LOW_CLOUD))
        assertFalse(EumetCacheIdentity.probe(EumetProduct.CLOUD_TYPE, cloudType.validEpochSeconds, place) ==
            EumetCacheIdentity.probe(EumetProduct.FOG_LOW_CLOUD, cloudType.validEpochSeconds, place))
    }

    @Test fun `cloud product selection uses fresh daylight solar events then coordinate fallback`() {
        fun weather(
            valid: Long,
            isDay: Boolean?,
            sunrise: Long? = null,
            sunset: Long? = null,
        ) = CurrentWeather(
            latitude = place.latitude, longitude = place.longitude,
            validEpochSeconds = valid, fetchedEpochSeconds = valid,
            temperatureC = null, pressureHpa = null, humidityPercent = null,
            uvIndex = null, isDay = isDay, windSpeedKmh = null, windFromDegrees = null,
            timeZone = "Europe/London", sunriseEpochSeconds = sunrise,
            sunsetEpochSeconds = sunset,
        )

        val noon = Instant.parse("2026-09-17T12:00:00Z").epochSecond
        val midnight = Instant.parse("2026-09-17T00:00:00Z").epochSecond
        assertEquals(EumetProduct.CLOUD_TYPE,
            CloudProductSelectionPolicy.preferred(weather(noon, true), place, noon))
        assertEquals(EumetProduct.FOG_LOW_CLOUD,
            CloudProductSelectionPolicy.preferred(weather(midnight, false), place, midnight))

        val sunrise = Instant.parse("2026-09-17T05:30:00Z").epochSecond
        val sunset = Instant.parse("2026-09-17T18:15:00Z").epochSecond
        val staleDaylight = weather(noon - 3_600, false, sunrise, sunset)
        assertEquals(EumetProduct.CLOUD_TYPE,
            CloudProductSelectionPolicy.preferred(staleDaylight, place, noon))
        assertEquals(EumetProduct.FOG_LOW_CLOUD,
            CloudProductSelectionPolicy.preferred(null, place, midnight))
        assertEquals(EumetProduct.CLOUD_TYPE,
            CloudProductSelectionPolicy.preferred(null, place, noon))
        assertEquals(listOf(EumetProduct.CLOUD_TYPE, EumetProduct.FOG_LOW_CLOUD),
            CloudProductSelectionPolicy.loadOrder(EumetProduct.CLOUD_TYPE))
        assertEquals(listOf(EumetProduct.FOG_LOW_CLOUD, EumetProduct.CLOUD_TYPE),
            CloudProductSelectionPolicy.loadOrder(EumetProduct.FOG_LOW_CLOUD))
    }

    @Test fun `layer and indicator settings have safe persisted defaults`() {
        assertEquals(RadarMapLayer.OFF, RadarMapLayer.decode(null))
        assertEquals(RadarMapLayer.OFF, RadarMapLayer.decode("unknown"))
        RadarMapLayer.entries.forEach { assertEquals(it, RadarMapLayer.decode(it.name)) }
        assertEquals(emptySet<RadarMapLayer>(), RadarMapLayerPreference.decode(null))
        assertEquals(emptySet<RadarMapLayer>(), RadarMapLayerPreference.decode(""))
        assertEquals(emptySet<RadarMapLayer>(), RadarMapLayerPreference.decode("OFF"))
        assertEquals(emptySet<RadarMapLayer>(), RadarMapLayerPreference.decode("corrupt"))
        assertEquals(emptySet<RadarMapLayer>(), RadarMapLayerPreference.decode("WIND,corrupt"))
        RadarMapLayer.overlays.forEach { layer ->
            assertEquals(setOf(layer), RadarMapLayerPreference.decode(layer.name))
        }
        assertEquals("Clouds", RadarMapLayer.FOG.label)
        assertEquals(setOf(RadarMapLayer.FOG), RadarMapLayerPreference.decode("FOG"))
        for (mask in 0 until (1 shl RadarMapLayer.overlays.size)) {
            val layers = RadarMapLayer.overlays.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val encoded = RadarMapLayerPreference.encode(layers)
            assertEquals(RadarMapLayer.overlays.filter(layers::contains).joinToString(",") { it.name }, encoded)
            assertEquals(layers, RadarMapLayerPreference.decode(encoded))
        }
        var toggled = emptySet<RadarMapLayer>()
        RadarMapLayer.overlays.forEach { layer ->
            toggled = RadarMapLayerPreference.toggled(toggled, layer, true)
        }
        assertEquals(RadarMapLayer.overlays.toSet(), toggled)
        assertEquals(setOf(RadarMapLayer.WIND, RadarMapLayer.FOG),
            RadarMapLayerPreference.toggled(toggled, RadarMapLayer.LIGHTNING, false))
        assertEquals(NowWeatherMetric.entries.toSet(),
            NowWeatherMetricPreference.decode(null))
        assertEquals(NowWeatherMetric.entries.toSet(), NowWeatherMetricPreference.decode("old-value"))
        assertFalse(NowWeatherMetric.WIND in NowWeatherMetricPreference.decode("TEMPERATURE,PRESSURE,HUMIDITY,UV_INDEX"))
        assertTrue(NowWeatherMetric.WIND in NowWeatherMetricPreference.decode("TEMPERATURE,WIND"))
        val chosen = setOf(NowWeatherMetric.TEMPERATURE, NowWeatherMetric.UV_INDEX)
        assertEquals(chosen, NowWeatherMetricPreference.decode(NowWeatherMetricPreference.encode(chosen)))
        assertEquals(emptySet<NowWeatherMetric>(), NowWeatherMetricPreference.decode(""))
    }
}
