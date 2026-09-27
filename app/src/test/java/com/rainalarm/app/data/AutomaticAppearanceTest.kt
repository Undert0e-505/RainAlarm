package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AutomaticAppearanceTest {
    private val sunrise = Instant.parse("2026-09-26T06:00:00Z").epochSecond
    private val sunset = Instant.parse("2026-09-26T18:00:00Z").epochSecond

    private fun weather(
        zone: String? = "Europe/London",
        rise: Long? = sunrise,
        set: Long? = sunset,
    ) = CurrentWeather(
        latitude = 51.5,
        longitude = -0.1,
        validEpochSeconds = sunrise,
        fetchedEpochSeconds = sunrise,
        temperatureC = null,
        pressureHpa = null,
        humidityPercent = null,
        uvIndex = null,
        isDay = null,
        windSpeedKmh = null,
        windFromDegrees = null,
        timeZone = zone,
        sunriseEpochSeconds = rise,
        sunsetEpochSeconds = set,
    )

    @Test fun `solar phase uses selected place cache and schedules exact next edge`() {
        val cached = weather()
        assertEquals(AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(sunrise - 1, cached, systemDark = false))
        assertEquals(AppearancePhase.DAY,
            AutomaticAppearancePolicy.phase(sunrise, cached, systemDark = true))
        assertEquals(AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(sunset, cached, systemDark = false))
        assertEquals(sunset, AutomaticAppearancePolicy.nextSwitch(sunrise + 1, cached))
        val projectedNextSunrise = Instant.ofEpochSecond(sunrise)
            .atZone(ZoneId.of("Europe/London"))
            .plusDays(1)
            .toEpochSecond()
        assertEquals(projectedNextSunrise, AutomaticAppearancePolicy.nextSwitch(sunset, cached))
        assertEquals(projectedNextSunrise,
            AutomaticAppearancePolicy.nextEvaluation(sunset, cached))
    }

    @Test fun `missing malformed or wrong-day solar facts use deterministic system fallback`() {
        assertEquals(AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(sunrise, weather(zone = null), systemDark = true))
        assertEquals(AppearancePhase.DAY,
            AutomaticAppearancePolicy.phase(sunrise, weather(rise = null), systemDark = false))
        assertEquals(AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(sunrise + 86_400, weather(), systemDark = true))
        assertNull(AutomaticAppearancePolicy.nextEvaluation(sunrise, weather(zone = "not/a-zone")))
    }

    @Test fun `optional boundary hysteresis is stable without hiding the eventual phase`() {
        assertEquals(
            AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(
                sunrise + 20,
                weather(),
                systemDark = false,
                previous = AppearancePhase.NIGHT,
            ),
        )
        assertEquals(
            AppearancePhase.DAY,
            AutomaticAppearancePolicy.phase(
                sunrise + 31,
                weather(),
                systemDark = true,
                previous = AppearancePhase.NIGHT,
            ),
        )
        assertEquals(
            AppearancePhase.DAY,
            AutomaticAppearancePolicy.phase(
                sunset + 20,
                weather(),
                systemDark = true,
                previous = AppearancePhase.DAY,
            ),
        )
        assertEquals(
            AppearancePhase.NIGHT,
            AutomaticAppearancePolicy.phase(
                sunset + 31,
                weather(),
                systemDark = false,
                previous = AppearancePhase.DAY,
            ),
        )
    }

    @Test fun `automatic profiles resolve atomically while manual mode is unchanged when off`() {
        val day = AppearanceProfile(
            AppearanceMode.DARK,
            ProfileMapAppearance.SLATE,
            NowCardAppearance.LIGHT,
            NowCardAppearance.DARK,
        )
        val automatic = AutomaticAppearancePreferences(enabled = true, day = day)
        val resolved = AutomaticAppearancePolicy.resolve(
            automatic,
            manualApp = AppearanceMode.LIGHT,
            manualMap = AppearanceMode.LIGHT,
            manualCompass = NowCardAppearance.FOLLOW_APP,
            manualGraph = NowCardAppearance.FOLLOW_APP,
            nowEpochSeconds = sunrise + 60,
            weather = weather(),
            systemDark = false,
        )
        assertEquals(AppearancePhase.DAY, resolved.phase)
        assertEquals(AppearanceMode.DARK, resolved.app)
        assertEquals(AppearanceMode.SLATE, resolved.map)
        assertEquals(NowCardAppearance.LIGHT, resolved.compass)
        assertEquals(NowCardAppearance.DARK, resolved.graph)

        val manual = AutomaticAppearancePolicy.resolve(
            AutomaticAppearancePreferences(enabled = false),
            AppearanceMode.LIGHT,
            AppearanceMode.SLATE,
            NowCardAppearance.DARK,
            NowCardAppearance.LIGHT,
            sunrise,
            weather(),
            systemDark = true,
        )
        assertNull(manual.phase)
        assertEquals(AppearanceMode.LIGHT, manual.app)
        assertEquals(AppearanceMode.SLATE, manual.map)
        assertEquals(NowCardAppearance.DARK, manual.compass)
        assertEquals(NowCardAppearance.LIGHT, manual.graph)
    }

    @Test fun `profile persistence codec clamps malformed and forbidden app modes per field`() {
        assertFalse(AutomaticAppearancePreferences().enabled)
        assertEquals(AppearanceProfile.DAY_DEFAULT, AppearanceProfilePreference.decode(
            "SLATE", "AUTO", "future", "future", AppearanceProfile.DAY_DEFAULT,
        ))
        val customFallback = AppearanceProfile(
            AppearanceMode.DARK,
            ProfileMapAppearance.SLATE,
            NowCardAppearance.DARK,
            NowCardAppearance.LIGHT,
        )
        assertEquals(customFallback, AppearanceProfilePreference.decode(
            null, null, null, null, customFallback,
        ))
        assertEquals(
            AppearanceProfile(
                AppearanceMode.LIGHT,
                ProfileMapAppearance.FOLLOW_APP,
                NowCardAppearance.SLATE,
                NowCardAppearance.DARK,
            ),
            AppearanceProfilePreference.decode(
                "LIGHT", "FOLLOW_APP", "SLATE", "DARK", customFallback,
            ),
        )
    }

    @Test fun `upgrade fallback maps existing manual choices without introducing recursive modes`() {
        assertEquals(
            AppearanceProfile(
                AppearanceMode.DARK,
                ProfileMapAppearance.SLATE,
                NowCardAppearance.LIGHT,
                NowCardAppearance.FOLLOW_APP,
            ),
            AppearanceProfilePreference.migrationFallback(
                "DARK", "SLATE", "LIGHT", "FOLLOW_APP", AppearanceProfile.DAY_DEFAULT,
            ),
        )
        assertEquals(
            AppearanceProfile(
                AppearanceMode.DARK,
                ProfileMapAppearance.FOLLOW_APP,
                NowCardAppearance.FOLLOW_APP,
                NowCardAppearance.FOLLOW_APP,
            ),
            AppearanceProfilePreference.migrationFallback(
                "FOLLOW_SYSTEM", "FOLLOW_SYSTEM", null, null, AppearanceProfile.NIGHT_DEFAULT,
            ),
        )
    }
}
