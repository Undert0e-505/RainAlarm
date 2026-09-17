package com.rainalarm.app.data

import com.rainalarm.app.alerts.RadarAlertEvaluation
import com.rainalarm.app.alerts.RainAlertDecisionEngine
import com.rainalarm.app.domain.RadarChartSeverity
import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.RainMinuteSeriesAnalyzer
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalRainChartTest {
    private val cardiff = SavedPlace("Cardiff", 51.4816, -3.1791)
    private val areaId = "UWZUK01360"
    private val now = Instant.parse("2026-09-17T02:10:00Z").epochSecond

    private fun payload(
        average: List<Int> = (0..60).map { if (it >= 11) 40 else 0 },
        minimum: List<Int> = average.map { if (it >= 11) 30 else 0 },
        maximum: List<Int> = average.map { if (it >= 11) 60 else 0 },
        start: String = "2026-09-17 02:10:00",
        created: String = "2026-09-17 02:10:00",
    ): String = """{"uwzid":"$areaId","domainMin":0,"domainMax":100,"interval":"60000",
        "startdtg":"$start","creationdtg":"$created","avg":$average,"min":$minimum,"max":$maximum} """

    @Test
    fun `area profile gives dry now plus eleven minute arrival and provider min max band`() {
        val lookup = """[{"AREA_TYPE":"UWZ","AREA_ID":"$areaId","CENTER_ID":"2"}]"""
        assertEquals(areaId, RegionalRainChartParser.lookupAreaId(lookup))
        val chart = RegionalRainChartParser.parseChart(payload(), areaId)
        val series = RegionalRainChartSeries.build(chart, now, null)
        assertEquals(RadarIntensityEncoding.REGIONAL_AREA_CHART, series.intensityEncoding)
        assertEquals(RainMinuteAvailability.AVAILABLE, series.availability)
        assertEquals(61, series.points.size)
        assertEquals(0f, series.points.first().average, 0f)
        assertEquals(0f, series.chartSeverity(series.points.first().average), 0f)
        val atArrival = series.points[11]
        assertEquals(0.3f, atArrival.minimum, 0.0001f)
        assertEquals(0.4f, atArrival.average, 0.0001f)
        assertEquals(0.6f, atArrival.maximum, 0.0001f)
        assertTrue(series.chartSeverity(atArrival.minimum) < series.chartSeverity(atArrival.average))
        assertTrue(series.chartSeverity(atArrival.average) < series.chartSeverity(atArrival.maximum))
        val analysis = requireNotNull(RainMinuteSeriesAnalyzer.analyze(series))
        assertFalse(analysis.rainingNow)
        assertEquals(11, analysis.arrivalMinute)
        assertEquals(11, (RainAlertDecisionEngine.evaluateMinuteSeries(series)
            as RadarAlertEvaluation.Approaching).etaStartMinutes)
        assertEquals(0f, RadarChartSeverity.forSeries(0.25f, series.intensityEncoding), 0f)
        assertEquals(1f / 3f, RadarChartSeverity.forSeries(0.5f, series.intensityEncoding), 0.00001f)
    }

    @Test
    fun `UTC creation and subminute alignment preserve only the available horizon`() {
        val average = (0..60).map { if (it <= 15) 20 else 30 }
        val chart = RegionalRainChartParser.parseChart(payload(
            average = average,
            minimum = average.map { it - 5 },
            maximum = average.map { it + 5 },
            created = "2026-09-17 02:22:31",
        ), areaId)
        val evaluatedAt = Instant.parse("2026-09-17T02:25:30Z").epochSecond
        val series = RegionalRainChartSeries.build(chart, evaluatedAt, 90.0)
        assertEquals(evaluatedAt, series.startEpochSeconds)
        assertEquals(Instant.parse("2026-09-17T02:22:31Z").epochSecond,
            series.latestObservationEpochSeconds)
        assertEquals(RainMinuteAvailability.PARTIAL, series.availability)
        assertEquals(45, series.points.size) // ends at +44; never pads to +60
        assertEquals(0.25f, series.points.first().average, 0.00001f)
        assertTrue(requireNotNull(RainMinuteSeriesAnalyzer.analyze(series)).rainingNow)
        assertEquals(90.0, series.travelBearingDegrees!!, 0.0)
    }

    @Test
    fun `negative raw provider samples are accepted and clamped only for display`() {
        // Distilled from the bundled retired-app chart fixture: domain 0..100,
        // with legitimate negative min and avg values near the beginning.
        val averages = (0..60).map { if (it == 0) -2 else if (it == 1) -1 else 30 }
        val minimums = (0..60).map { if (it == 0) -4 else if (it == 1) -3 else 25 }
        val maximums = (0..60).map { if (it == 0) 1 else if (it == 1) 2 else 35 }
        val chart = RegionalRainChartParser.parseChart(payload(averages, minimums, maximums), areaId)
        assertEquals(-4, chart.minimums.first())
        assertEquals(-2, chart.averages.first())
        val series = RegionalRainChartSeries.build(chart, now, null)
        assertEquals(0f, series.points.first().minimum, 0f)
        assertEquals(0f, series.points.first().average, 0f)
        assertEquals(0.01f, series.points.first().maximum, 0.00001f)
        assertEquals(2, requireNotNull(RainMinuteSeriesAnalyzer.analyze(series)).arrivalMinute)
    }

    @Test
    fun `raw values remain bounded and nearby locations never share cached area id`() {
        val excessive = payload(
            average = List(61) { -201 },
            minimum = List(61) { -201 },
            maximum = List(61) { -201 },
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RegionalRainChartParser.parseChart(excessive, areaId)
        }
        val nearby = SavedPlace("Near Cardiff", 51.4836, -3.1791)
        val cache = RegionalAreaIdCache()
        cache.put(cardiff, areaId, now)
        assertEquals(areaId, cache.get(cardiff, now))
        assertEquals(null, cache.get(nearby, now))
        cache.put(nearby, "UWZUK01361", now)
        assertEquals(areaId, cache.get(cardiff, now))
        assertEquals("UWZUK01361", cache.get(nearby, now))
    }

    @Test
    fun `stale malformed and unmatched area responses use unchanged raster fallback`() = runBlocking {
        val fallback = RainMinuteSeries(now, (0..60).map { RainMinutePoint(it, 0f, 0f, 0f, it > 0) },
            "MeteoGroup provider forecast", 1.0, RainMinuteAvailability.AVAILABLE)
        var lookups = 0
        var response = payload(created = "2026-09-17 01:50:00")
        val endpoint = object : RegionalRainChartEndpoint {
            override suspend fun lookup(place: SavedPlace): String {
                lookups++
                return """[{"AREA_TYPE":"UWZ","AREA_ID":"$areaId"}]"""
            }
            override suspend fun chart(areaId: String): String = response
        }
        val service = RegionalRainChartService(endpoint, RegionalAreaIdCache())
        assertSame(fallback, service.preferChart(cardiff, now, fallback))
        response = payload(minimum = List(61) { 90 }) // malformed min > avg
        assertSame(fallback, service.preferChart(cardiff, now, fallback))
        response = payload().replace(areaId, "UWZUK99999")
        assertSame(fallback, service.preferChart(cardiff, now, fallback))
        assertEquals(1, lookups) // area ID cached; chart itself is freshly requested
    }

    @Test
    fun `cleartext exception is limited to chart host and lookup remains HTTPS`() {
        val config = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
        ).first(File::isFile).readText()
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"false\""))
        assertTrue(config.contains("<domain includeSubdomains=\"false\">android.weatherpro.weatherservice.meteogroup.de</domain>"))
        assertEquals(1, Regex("<domain ").findAll(config).count())
        val source = listOf(File("src/main/java/com/rainalarm/app/data/RegionalRainChart.kt"),
            File("app/src/main/java/com/rainalarm/app/data/RegionalRainChart.kt"))
            .first(File::isFile).readText()
        assertTrue(source.contains("https://feed.alertspro.meteogroup.com"))
        assertTrue(source.contains("http://android.weatherpro.weatherservice.meteogroup.de"))
    }
}
