package com.rainalarm.app.data

import com.rainalarm.app.domain.RainStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpenMeteoMapperTest {
    @Test
    fun parsesProviderPayloadAndAppliesTimezone() {
        val now = Instant.parse("2026-09-14T10:55:00Z")
        val body = """
            {
              "timezone": "Europe/London",
              "minutely_15": {
                "time": ["2026-09-14T12:00", "2026-09-14T12:15"],
                "precipitation": [0.0, 0.3]
              },
              "hourly": {
                "time": ["2026-09-14T12:00"],
                "precipitation_probability": [72]
              }
            }
        """.trimIndent()

        val result = OpenMeteoMapper.fromJson(body, now, now)

        assertEquals(Instant.parse("2026-09-14T11:00:00Z"), result.slots.first().startsAt)
        assertEquals(72, result.slots.last().probabilityPercent)
        assertEquals(RainStatus.APPROACHING, result.summary.status)
    }

    @Test
    fun missingMinutelyArraysMapToUnavailable() {
        val now = Instant.parse("2026-09-14T10:00:00Z")
        val result = OpenMeteoMapper.fromJson("""{"timezone":"UTC"}""", now, now)
        assertEquals(RainStatus.UNAVAILABLE, result.summary.status)
    }

    @Test
    fun forecastUrlUsesSelectedCoordinatesAndEncodesLists() {
        val url = buildForecastUrl(SavedPlace("North coast", 55.125, -3.75))

        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=55.125"))
        assertTrue(url.contains("longitude=-3.75"))
        assertTrue(url.contains("minutely_15=precipitation%2Crain%2Cshowers%2Csnowfall"))
        assertTrue(url.contains("timezone=auto"))
    }

    @Test
    fun forecastUrlRejectsCleartextEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            buildForecastUrl(DEFAULT_PLACE, "http://example.invalid/forecast")
        }
    }

    @Test
    fun mapperCarriesSelectedPlaceIntoSnapshot() {
        val now = Instant.parse("2026-09-14T10:00:00Z")
        val place = SavedPlace("Current location", 53.4, -2.9, isCurrentLocation = true)

        val result = OpenMeteoMapper.fromJson(
            body = """{"timezone":"UTC"}""",
            fetchedAt = now,
            now = now,
            place = place,
        )

        assertEquals("Current location", result.locationName)
    }

    @Test
    fun placeValidationAndDefaultStateAreExplicit() {
        assertEquals("London", DEFAULT_PLACE.name)
        assertEquals(false, DEFAULT_PLACE.isCurrentLocation)
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlace("Bad latitude", 91.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SavedPlace("Bad longitude", 0.0, Double.NaN)
        }
    }
}
