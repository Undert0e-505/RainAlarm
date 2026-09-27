package com.rainalarm.app.data

import java.time.Instant
import java.time.ZoneId

enum class AppearancePhase { DAY, NIGHT }

/** Map profiles follow the resolved app surface, never the automatic selector itself. */
enum class ProfileMapAppearance(val label: String) {
    FOLLOW_APP("Follow app"), LIGHT("Light"), DARK("Dark"), SLATE("Slate");

    companion object {
        fun decode(raw: String?, fallback: ProfileMapAppearance): ProfileMapAppearance =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

data class AppearanceProfile(
    val app: AppearanceMode,
    val map: ProfileMapAppearance,
    val compass: NowCardAppearance,
    val graph: NowCardAppearance,
) {
    init {
        require(app == AppearanceMode.LIGHT || app == AppearanceMode.DARK) {
            "Automatic profile app appearance must be concrete"
        }
    }

    companion object {
        val DAY_DEFAULT = AppearanceProfile(
            app = AppearanceMode.LIGHT,
            map = ProfileMapAppearance.LIGHT,
            compass = NowCardAppearance.FOLLOW_APP,
            graph = NowCardAppearance.FOLLOW_APP,
        )
        val NIGHT_DEFAULT = AppearanceProfile(
            app = AppearanceMode.DARK,
            map = ProfileMapAppearance.DARK,
            compass = NowCardAppearance.FOLLOW_APP,
            graph = NowCardAppearance.FOLLOW_APP,
        )
    }
}

data class AutomaticAppearancePreferences(
    val enabled: Boolean = false,
    val day: AppearanceProfile = AppearanceProfile.DAY_DEFAULT,
    val night: AppearanceProfile = AppearanceProfile.NIGHT_DEFAULT,
)

/** Defensive on-disk codec kept separate from DataStore so migrations are unit-testable. */
internal object AppearanceProfilePreference {
    fun migrationFallback(
        appRaw: String?,
        mapRaw: String?,
        compassRaw: String?,
        graphRaw: String?,
        phaseDefault: AppearanceProfile,
    ): AppearanceProfile {
        val app = AppearanceMode.entries.firstOrNull { it.name == appRaw }
            ?.takeIf { it == AppearanceMode.LIGHT || it == AppearanceMode.DARK }
            ?: phaseDefault.app
        val map = when (AppearanceMode.entries.firstOrNull { it.name == mapRaw }) {
            AppearanceMode.LIGHT -> ProfileMapAppearance.LIGHT
            AppearanceMode.DARK -> ProfileMapAppearance.DARK
            AppearanceMode.SLATE -> ProfileMapAppearance.SLATE
            AppearanceMode.FOLLOW_SYSTEM -> ProfileMapAppearance.FOLLOW_APP
            null -> phaseDefault.map
        }
        return AppearanceProfile(
            app = app,
            map = map,
            compass = NowCardAppearance.entries.firstOrNull { it.name == compassRaw }
                ?: phaseDefault.compass,
            graph = NowCardAppearance.entries.firstOrNull { it.name == graphRaw }
                ?: phaseDefault.graph,
        )
    }

    fun decode(
        appRaw: String?,
        mapRaw: String?,
        compassRaw: String?,
        graphRaw: String?,
        fallback: AppearanceProfile,
    ): AppearanceProfile {
        val app = AppearanceMode.entries.firstOrNull { it.name == appRaw }
            ?.takeIf { it == AppearanceMode.LIGHT || it == AppearanceMode.DARK }
            ?: fallback.app
        fun card(raw: String?, fieldFallback: NowCardAppearance): NowCardAppearance =
            NowCardAppearance.entries.firstOrNull { it.name == raw } ?: fieldFallback
        return AppearanceProfile(
            app = app,
            map = ProfileMapAppearance.decode(mapRaw, fallback.map),
            compass = card(compassRaw, fallback.compass),
            graph = card(graphRaw, fallback.graph),
        )
    }
}

data class ResolvedAppearance(
    val phase: AppearancePhase?,
    val app: AppearanceMode,
    val map: AppearanceMode,
    val compass: NowCardAppearance,
    val graph: NowCardAppearance,
    val nextSwitchEpochSeconds: Long?,
)

object AutomaticAppearancePolicy {
    private const val BOUNDARY_HYSTERESIS_SECONDS = 30L

    fun phase(
        nowEpochSeconds: Long,
        weather: CurrentWeather?,
        systemDark: Boolean,
        previous: AppearancePhase? = null,
    ): AppearancePhase {
        val sunrise = weather?.sunriseEpochSeconds
        val sunset = weather?.sunsetEpochSeconds
        val zone = weather?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val solarDateMatches = zone != null && sunrise != null && sunset != null &&
            Instant.ofEpochSecond(sunrise).atZone(zone).toLocalDate() ==
            Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
        if (solarDateMatches && sunrise < sunset) {
            if (previous != null &&
                (kotlin.math.abs(nowEpochSeconds - sunrise) <= BOUNDARY_HYSTERESIS_SECONDS ||
                    kotlin.math.abs(nowEpochSeconds - sunset) <= BOUNDARY_HYSTERESIS_SECONDS)
            ) return previous
            return if (nowEpochSeconds in sunrise until sunset) AppearancePhase.DAY else AppearancePhase.NIGHT
        }
        return if (systemDark) AppearancePhase.NIGHT else AppearancePhase.DAY
    }

    fun nextSwitch(nowEpochSeconds: Long, weather: CurrentWeather?): Long? {
        val sunrise = weather?.sunriseEpochSeconds ?: return null
        val sunset = weather.sunsetEpochSeconds ?: return null
        val zone = weather.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        val today = Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
        val sunriseAtPlace = Instant.ofEpochSecond(sunrise).atZone(zone)
        val sunsetAtPlace = Instant.ofEpochSecond(sunset).atZone(zone)
        if (sunriseAtPlace.toLocalDate() != today || sunsetAtPlace.toLocalDate() != today ||
            sunrise >= sunset
        ) {
            return null
        }
        return sequenceOf(sunrise, sunset).filter { it > nowEpochSeconds }.minOrNull()
            // The selected-place cache contains today's pair. After sunset, preserve the
            // published local sunrise clock time across the next local date until the regular
            // point-weather refresh supplies tomorrow's exact solar facts. Zoned plusDays also
            // respects a daylight-saving boundary, unlike adding a fixed 86,400 seconds.
            ?: sunriseAtPlace.plusDays(1).toEpochSecond().takeIf { it > nowEpochSeconds }
    }

    /** Wake at the next known solar edge, or just after local midnight to consume tomorrow's cache. */
    fun nextEvaluation(nowEpochSeconds: Long, weather: CurrentWeather?): Long? {
        nextSwitch(nowEpochSeconds, weather)?.let { return it }
        val zone = weather?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        return Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .plusSeconds(2)
            .toEpochSecond()
    }

    fun resolve(
        automatic: AutomaticAppearancePreferences,
        manualApp: AppearanceMode,
        manualMap: AppearanceMode,
        manualCompass: NowCardAppearance,
        manualGraph: NowCardAppearance,
        nowEpochSeconds: Long,
        weather: CurrentWeather?,
        systemDark: Boolean,
        previousPhase: AppearancePhase? = null,
    ): ResolvedAppearance {
        if (!automatic.enabled) return ResolvedAppearance(
            phase = null,
            app = manualApp,
            map = manualMap,
            compass = manualCompass,
            graph = manualGraph,
            nextSwitchEpochSeconds = null,
        )
        val phase = phase(nowEpochSeconds, weather, systemDark, previousPhase)
        val profile = if (phase == AppearancePhase.DAY) automatic.day else automatic.night
        val map = when (profile.map) {
            ProfileMapAppearance.FOLLOW_APP -> profile.app
            ProfileMapAppearance.LIGHT -> AppearanceMode.LIGHT
            ProfileMapAppearance.DARK -> AppearanceMode.DARK
            ProfileMapAppearance.SLATE -> AppearanceMode.SLATE
        }
        return ResolvedAppearance(
            phase = phase,
            app = profile.app,
            map = map,
            compass = profile.compass,
            graph = profile.graph,
            nextSwitchEpochSeconds = nextSwitch(nowEpochSeconds, weather),
        )
    }
}
