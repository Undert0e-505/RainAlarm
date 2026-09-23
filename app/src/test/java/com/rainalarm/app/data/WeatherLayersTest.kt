package com.rainalarm.app.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.ParserConfigurationException
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
        fogTime: String = "2026-09-17T02:30:00.000Z") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms"><Capability><Layer>
          <Layer><Name>mtg_fd:li_afa</Name><EX_GeographicBoundingBox>
          <westBoundLongitude>-70</westBoundLongitude><eastBoundLongitude>70</eastBoundLongitude>
          <southBoundLatitude>-70</southBoundLatitude><northBoundLatitude>70</northBoundLatitude>
          </EX_GeographicBoundingBox><Dimension name="time" default="$lightningTime" units="ISO8601" nearestValue="1">
          2025-05-30T15:00:00.000Z/$lightningTime/PT5M</Dimension></Layer>
          <Layer><Name>mtg_fd:rgb_fog</Name><EX_GeographicBoundingBox>
          <westBoundLongitude>-81.27779388427734</westBoundLongitude>
          <eastBoundLongitude>81.28072357177734</eastBoundLongitude>
          <southBoundLatitude>-77.35063934326172</southBoundLatitude>
          <northBoundLatitude>77.35639190673828</northBoundLatitude>
          </EX_GeographicBoundingBox><Dimension name="time" default="$fogTime" units="ISO8601" nearestValue="1">
          2025-06-06T18:40:00.000Z/$fogTime/PT10M</Dimension></Layer>
        </Layer></Capability></WMS_Capabilities>""".trimIndent().toByteArray()

    @Test fun `capabilities select exact layer valid time and coverage`() {
        val flashes = EumetCapabilities.parse(xml(), RadarMapLayer.LIGHTNING)
        val fog = EumetCapabilities.parse(xml(), RadarMapLayer.FOG)
        assertEquals(Instant.parse("2026-09-17T02:45:00Z").epochSecond, flashes.validEpochSeconds)
        assertTrue(flashes.freshAt(now))
        assertTrue(fog.freshAt(now))
        assertTrue(flashes.covers(place))
        assertFalse(flashes.covers(SavedPlace("Tokyo", 35.6, 139.7)))
        assertFalse(flashes.freshAt(now + 901))
        assertFalse(fog.freshAt(now + 1_801))
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

    @Test fun `pinned WMS tile and probe URLs remain HTTPS and spatial`() {
        val metadata = EumetCapabilities.parse(xml(), RadarMapLayer.LIGHTNING)
        val tile = metadata.tileUrl()
        assertTrue(tile.startsWith("https://view.eumetsat.int/geoserver/wms?"))
        assertTrue(tile.contains("{bbox-epsg-3857}"))
        assertTrue(tile.contains("mtg_fd%3Ali_afa"))
        assertTrue(tile.contains("time=2026-09-17T02%3A45%3A00Z"))
        val renderer = listOf(File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")).first(File::isFile).readText()
        assertTrue(renderer.contains("TileSet(\"2.2.0\", satellite.tileUrl())"))
        assertTrue(renderer.contains("RasterSource(sourceId, tiles, 256)"))
        val probe = metadata.probeUrl(place)
        assertFalse(probe.contains("{bbox-epsg-3857}"))
        assertTrue(probe.contains("&width=64&height=64"))
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
