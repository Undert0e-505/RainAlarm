package com.rainalarm.app.widget

import com.rainalarm.app.alerts.LightningDetectionPolicy
import com.rainalarm.app.alerts.QuietHours
import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.domain.RadarIntensityEncoding
import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class WidgetLocationMode { FIXED, FOLLOW_APP }

/**
 * Self-contained widget target. The legacy place id remains useful for rename reconciliation, but
 * acquisition never depends on the app's current selection or a live device fix.
 */
@Serializable
data class WidgetSavedPlaceTarget(
    val stableId: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
) {
    val valid: Boolean get() = stableId.isNotBlank() && displayName.isNotBlank() &&
        latitude.isFinite() && latitude in -85.05112878..85.05112878 &&
        longitude.isFinite() && longitude in -180.0..180.0

    fun withCurrentName(places: PlaceCollection): WidgetSavedPlaceTarget =
        places.places.firstOrNull { it.id == stableId }
            ?.let { copy(displayName = it.name) } ?: this

    companion object {
        fun from(place: SavedPlace): WidgetSavedPlaceTarget = WidgetSavedPlaceTarget(
            place.id, place.name, place.latitude, place.longitude,
        )
    }
}

/**
 * Serialization-only compatibility with widgets configured before 0.9.0.  Rendering is now
 * unconditionally rain-first; this value is deliberately ignored by runtime presentation.
 */
@Serializable
enum class WidgetPrimaryContent { SMART, RAIN, LIGHTNING, TEMPERATURE }

@Serializable
data class RainAlarmWidgetConfig(
    val appWidgetId: Int,
    // Retained only so old 0.4.1 JSON can be decoded and migrated once. Runtime resolution never
    // follows either field after [savedPlace] has been populated.
    val locationMode: WidgetLocationMode = WidgetLocationMode.FOLLOW_APP,
    val fixedPlaceId: String? = null,
    val primaryContent: WidgetPrimaryContent = WidgetPrimaryContent.SMART,
    val backgroundOpacityPercent: Int = 100,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinuteOfDay: Int = 22 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
    val savedPlace: WidgetSavedPlaceTarget? = null,
    val notificationsEnabled: Boolean = true,
    val includeLightningNotifications: Boolean = false,
) {
    val opacity: Float get() = backgroundOpacityPercent.coerceIn(0, 100) / 100f
    val quietHours: QuietHours get() = QuietHours(
        quietHoursEnabled, quietStartMinuteOfDay, quietEndMinuteOfDay,
    )
    val valid: Boolean get() = appWidgetId >= 0 && savedPlace?.valid == true &&
        backgroundOpacityPercent in 0..100 &&
        (!notificationsEnabled || !quietHoursEnabled || quietHours.valid)

    /** Lightning is visible only as secondary text on non-compact widgets. */
    fun requiresLightningDisplay(widthDp: Int? = null): Boolean = widthDp == null ||
        WidgetResponsivePolicy.sizeClass(widthDp) != WidgetSizeClass.COMPACT

    /** Compact widgets fetch Lightning only when their own notification subscription asks for it. */
    fun requiresLightningAcquisition(widthDp: Int? = null): Boolean =
        requiresLightningDisplay(widthDp) ||
            (notificationsEnabled && includeLightningNotifications)
}

@Serializable
enum class WidgetTargetProblem { CONFIGURATION_REQUIRED }

data class FrozenMonitorTarget(
    val stableId: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val selectedProvider: RadarProviderKind,
    val radiusKilometres: Double = LightningDetectionPolicy.defaultRadiusKilometres,
) {
    val key: String
        get() = "$stableId:${"%.5f".format(java.util.Locale.ROOT, latitude)}:" +
            "${"%.5f".format(java.util.Locale.ROOT, longitude)}:${selectedProvider.name}:" +
            "r${"%.1f".format(java.util.Locale.ROOT, radiusKilometres)}"

    /** Provider-independent identity for shared rain/lightning episode evidence. */
    val monitoringKey: String
        get() = "${"%.5f".format(java.util.Locale.ROOT, latitude)}:" +
            "${"%.5f".format(java.util.Locale.ROOT, longitude)}:" +
            "r${"%.1f".format(java.util.Locale.ROOT, radiusKilometres)}"

    fun asPlace(): SavedPlace = SavedPlace(
        name = displayName,
        latitude = latitude,
        longitude = longitude,
        id = stableId,
        isCurrentLocation = stableId == CURRENT_LOCATION_ID,
    )
}

sealed interface WidgetTargetResolution {
    data class Resolved(val target: FrozenMonitorTarget) : WidgetTargetResolution
    data class Unavailable(val problem: WidgetTargetProblem) : WidgetTargetResolution
}

object WidgetTargetResolver {
    fun resolve(
        config: RainAlarmWidgetConfig,
        places: PlaceCollection,
        provider: RadarProviderKind,
    ): WidgetTargetResolution {
        val place = config.savedPlace?.takeIf(WidgetSavedPlaceTarget::valid)
            ?.withCurrentName(places)
            ?: return WidgetTargetResolution.Unavailable(WidgetTargetProblem.CONFIGURATION_REQUIRED)
        return WidgetTargetResolution.Resolved(
            FrozenMonitorTarget(
                stableId = place.stableId,
                displayName = place.displayName,
                latitude = place.latitude,
                longitude = place.longitude,
                selectedProvider = provider,
            ),
        )
    }

}

/** Pure, idempotent migration rule for widgets created before targets were self-contained. */
object WidgetLegacyTargetMigrationPolicy {
    fun resolve(
        config: RainAlarmWidgetConfig,
        places: PlaceCollection,
        startupDefaultId: String?,
    ): WidgetSavedPlaceTarget? {
        config.savedPlace?.takeIf(WidgetSavedPlaceTarget::valid)?.let { return it }
        val savedPlaces = places.places.filterNot { it.id == CURRENT_LOCATION_ID || it.isCurrentLocation }
        val defaultPlace = savedPlaces.firstOrNull { it.id == startupDefaultId }
            ?: savedPlaces.firstOrNull { it.pinned }
            ?: savedPlaces.firstOrNull()
        val selectedSaved = savedPlaces.firstOrNull { it.id == places.selectedId }
        val legacyFixed = config.fixedPlaceId?.let { id ->
            savedPlaces.firstOrNull { it.id == id }
        }
        val target = when (config.locationMode) {
            WidgetLocationMode.FIXED -> legacyFixed ?: defaultPlace
            WidgetLocationMode.FOLLOW_APP -> selectedSaved ?: defaultPlace
        } ?: return null
        return WidgetSavedPlaceTarget.from(target)
    }
}

enum class WidgetSizeClass { COMPACT, SMALL, MEDIUM, EXPANDED }

object WidgetResponsivePolicy {
    /** The three one-row layouts share one visual compass size; compact fills its square. */
    const val textBearingCompassDiameterDp = 88
    const val textBearingGapDp = 6
    const val containerHorizontalPaddingDp = 20
    const val expandedStatusWidthDp = 88
    const val expandedFixedContentWidthDp = containerHorizontalPaddingDp +
        textBearingCompassDiameterDp + textBearingGapDp +
        expandedStatusWidthDp + textBearingGapDp

    fun sizeClass(widthDp: Int): WidgetSizeClass = when {
        widthDp < 150 -> WidgetSizeClass.COMPACT
        widthDp < 250 -> WidgetSizeClass.SMALL
        widthDp < 340 -> WidgetSizeClass.MEDIUM
        else -> WidgetSizeClass.EXPANDED
    }

    fun visibleGroups(widthDp: Int): Int = when (sizeClass(widthDp)) {
        WidgetSizeClass.COMPACT -> 1
        WidgetSizeClass.SMALL -> 4
        WidgetSizeClass.MEDIUM -> 4
        WidgetSizeClass.EXPANDED -> 5
    }

    fun compassDiameterDp(sizeClass: WidgetSizeClass): Int? = when (sizeClass) {
        WidgetSizeClass.COMPACT -> null
        WidgetSizeClass.SMALL,
        WidgetSizeClass.MEDIUM,
        WidgetSizeClass.EXPANDED,
        -> textBearingCompassDiameterDp
    }

    fun textStatusWidthDp(widgetWidthDp: Int): Int =
        (widgetWidthDp - containerHorizontalPaddingDp -
            textBearingCompassDiameterDp - textBearingGapDp).coerceAtLeast(0)
}

/** Pixel geometry shared by responsive layout decisions and the bitmap renderer. */
object WidgetGraphLayoutPolicy {
    const val minimumGraphWidthDp = 88
    const val graphHeightDp = 76

    fun widthDp(widgetWidthDp: Int): Int =
        (widgetWidthDp - WidgetResponsivePolicy.expandedFixedContentWidthDp)
            .coerceAtLeast(minimumGraphWidthDp)

    fun pixels(dp: Int, density: Float): Int =
        (dp * density.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1)

    fun sampleX(index: Int, sampleCount: Int, widthPx: Int, insetPx: Float): Float {
        require(index in 0 until sampleCount && sampleCount > 1 && widthPx > 0)
        return insetPx + index.toFloat() / (sampleCount - 1) * (widthPx - insetPx * 2f)
    }

    fun sampleY(value: Float, heightPx: Int, insetPx: Float): Float {
        require(heightPx > 0)
        return insetPx + (1f - value.coerceIn(0f, 1f)) * (heightPx - insetPx * 2f)
    }
}

object WidgetOpacityPolicy {
    fun decode(value: Int): Int = value.coerceIn(0, 100)
    fun applyToArgb(argb: Int, percent: Int): Int =
        (WidgetOpacityPolicy.decode(percent) * 255 / 100 shl 24) or (argb and 0x00ffffff)
}

@Serializable
enum class WidgetLightningState { DETECTED, NO_DETECTION, UNAVAILABLE, NOT_REQUESTED }

@Serializable
data class WidgetMinutePoint(
    val minute: Int,
    val minimum: Float,
    val average: Float,
    val maximum: Float,
    val forecast: Boolean,
    val likelySnow: Boolean = false,
)

/** Compact normalized cache: enough to reproduce Now semantics without retaining provider images. */
@Serializable
data class WidgetWeatherSnapshot(
    val targetKey: String,
    val targetStableId: String,
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val provider: RadarProviderKind,
    val updatedEpochSeconds: Long,
    val lastSuccessfulEpochSeconds: Long,
    val seriesStartEpochSeconds: Long? = null,
    val seriesSourceLabel: String? = null,
    val seriesConfidence: Double = 0.0,
    val seriesAvailability: RainMinuteAvailability = RainMinuteAvailability.UNAVAILABLE,
    val seriesUnavailableReason: String? = null,
    val latestObservationEpochSeconds: Long? = null,
    val sourceBearingDegrees: Double? = null,
    val intensityEncoding: RadarIntensityEncoding = RadarIntensityEncoding.REGIONAL_GRAYSCALE,
    val points: List<WidgetMinutePoint> = emptyList(),
    val temperatureC: Double? = null,
    val temperatureUpdatedEpochSeconds: Long? = null,
    val lightning: WidgetLightningState = WidgetLightningState.NOT_REQUESTED,
    val lightningFrameEpochSeconds: Long? = null,
    val updating: Boolean = false,
    val updateUnavailable: Boolean = false,
    val generation: Long = 0L,
) {
    fun rainSeries(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L): RainMinuteSeries? {
        val start = seriesStartEpochSeconds ?: return null
        val stored = runCatching {
            RainMinuteSeries(
                startEpochSeconds = start,
                points = points.map {
                    RainMinutePoint(
                        it.minute,
                        it.minimum,
                        it.average,
                        it.maximum,
                        it.forecast,
                        it.likelySnow,
                    )
                },
                sourceLabel = seriesSourceLabel.orEmpty(),
                confidence = seriesConfidence.coerceIn(0.0, 1.0),
                availability = seriesAvailability,
                unavailableReason = seriesUnavailableReason,
                latestObservationEpochSeconds = latestObservationEpochSeconds ?: start,
                sourceBearingDegrees = sourceBearingDegrees,
                intensityEncoding = intensityEncoding,
            )
        }.getOrNull()
        return stored?.let { WidgetSnapshotTimePolicy.currentSeries(it, nowEpochSeconds) }
    }

    companion object {
        fun from(
            target: FrozenMonitorTarget,
            series: RainMinuteSeries,
            temperatureC: Double?,
            lightning: WidgetLightningState,
            lightningFrameEpochSeconds: Long?,
            updatedEpochSeconds: Long,
            generation: Long,
        ) = WidgetWeatherSnapshot(
            targetKey = target.key,
            targetStableId = target.stableId,
            placeName = target.displayName,
            latitude = target.latitude,
            longitude = target.longitude,
            provider = target.selectedProvider,
            updatedEpochSeconds = updatedEpochSeconds,
            lastSuccessfulEpochSeconds = updatedEpochSeconds.takeIf {
                series.availability != RainMinuteAvailability.UNAVAILABLE ||
                    temperatureC != null || lightning == WidgetLightningState.DETECTED ||
                    lightning == WidgetLightningState.NO_DETECTION
            } ?: 0L,
            seriesStartEpochSeconds = series.startEpochSeconds,
            seriesSourceLabel = series.sourceLabel,
            seriesConfidence = series.confidence,
            seriesAvailability = series.availability,
            seriesUnavailableReason = series.unavailableReason,
            latestObservationEpochSeconds = series.latestObservationEpochSeconds,
            sourceBearingDegrees = series.sourceBearingDegrees,
            intensityEncoding = series.intensityEncoding,
            points = series.points.map {
                WidgetMinutePoint(
                    it.minute,
                    it.minimum,
                    it.average,
                    it.maximum,
                    it.forecast,
                    it.likelySnow,
                )
            },
            temperatureC = temperatureC,
            temperatureUpdatedEpochSeconds = updatedEpochSeconds.takeIf { temperatureC != null },
            lightning = lightning,
            lightningFrameEpochSeconds = lightningFrameEpochSeconds,
            generation = generation,
        )
    }
}

/** Keeps the last coherent rain presentation visible while a replacement attempt fails. */
object WidgetSnapshotCachePolicy {
    const val HARD_EXPIRY_SECONDS = 3 * 60 * 60L

    fun merge(
        previous: WidgetWeatherSnapshot?,
        incoming: WidgetWeatherSnapshot,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): WidgetWeatherSnapshot {
        if (previous == null || previous.targetKey != incoming.targetKey) return incoming
        val previousRetained = previous.lastSuccessfulEpochSeconds > 0L &&
            nowEpochSeconds - previous.lastSuccessfulEpochSeconds in 0..HARD_EXPIRY_SECONDS
        val rainFailed = incoming.seriesAvailability == RainMinuteAvailability.UNAVAILABLE
        val currentRain = incoming.rainSeries(nowEpochSeconds)
        val incomingTemperatureFresh = incoming.temperatureC != null &&
            WidgetSnapshotTimePolicy.temperatureFresh(incoming, nowEpochSeconds)
        val previousTemperatureFresh = previous.temperatureC != null && previousRetained &&
            WidgetSnapshotTimePolicy.temperatureFresh(previous, nowEpochSeconds)
        val incomingLightningFresh = WidgetSnapshotTimePolicy.lightningFresh(incoming, nowEpochSeconds)
        val previousLightningFresh = previousRetained &&
            WidgetSnapshotTimePolicy.lightningFresh(previous, nowEpochSeconds)
        val merged = if (rainFailed && previousRetained &&
            previous.seriesAvailability != RainMinuteAvailability.UNAVAILABLE
        ) incoming.copy(
            seriesStartEpochSeconds = previous.seriesStartEpochSeconds,
            seriesSourceLabel = previous.seriesSourceLabel,
            seriesConfidence = previous.seriesConfidence,
            seriesAvailability = previous.seriesAvailability,
            seriesUnavailableReason = previous.seriesUnavailableReason,
            latestObservationEpochSeconds = previous.latestObservationEpochSeconds,
            sourceBearingDegrees = previous.sourceBearingDegrees,
            intensityEncoding = previous.intensityEncoding,
            points = previous.points,
            updateUnavailable = false,
        ) else incoming
        val withIndependentStreams = merged.copy(
            temperatureC = merged.temperatureC ?: previous.temperatureC.takeIf {
                previousTemperatureFresh
            },
            temperatureUpdatedEpochSeconds = merged.temperatureUpdatedEpochSeconds ?:
                WidgetSnapshotTimePolicy.temperatureTimestamp(previous)
                    .takeIf { previousTemperatureFresh },
            lightning = if (merged.lightning == WidgetLightningState.UNAVAILABLE &&
                previousLightningFresh
            ) {
                previous.lightning
            } else merged.lightning,
            lightningFrameEpochSeconds = merged.lightningFrameEpochSeconds ?:
                previous.lightningFrameEpochSeconds.takeIf { previousLightningFresh },
        )
        val hasCurrentData = withIndependentStreams.rainSeries(nowEpochSeconds) != null ||
            (withIndependentStreams.temperatureC != null &&
                WidgetSnapshotTimePolicy.temperatureFresh(withIndependentStreams, nowEpochSeconds)) ||
            WidgetSnapshotTimePolicy.lightningFresh(withIndependentStreams, nowEpochSeconds)
        val incomingSucceeded = currentRain != null || incomingTemperatureFresh || incomingLightningFresh
        return withIndependentStreams.copy(
            updatedEpochSeconds = if (incomingSucceeded) incoming.updatedEpochSeconds
                else previous.updatedEpochSeconds,
            lastSuccessfulEpochSeconds = if (incomingSucceeded) incoming.lastSuccessfulEpochSeconds
                else previous.lastSuccessfulEpochSeconds,
            updateUnavailable = !hasCurrentData,
        )
    }

    fun forDisplay(
        snapshot: WidgetWeatherSnapshot,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): WidgetWeatherSnapshot {
        val storageExpired = snapshot.lastSuccessfulEpochSeconds <= 0L ||
            nowEpochSeconds - snapshot.lastSuccessfulEpochSeconds !in 0..HARD_EXPIRY_SECONDS
        if (storageExpired) return snapshot.copy(
                seriesAvailability = RainMinuteAvailability.UNAVAILABLE,
                points = emptyList(),
                temperatureC = null,
                lightning = WidgetLightningState.UNAVAILABLE,
                updateUnavailable = true,
            )
        val currentRain = snapshot.rainSeries(nowEpochSeconds)
        val temperatureFresh = WidgetSnapshotTimePolicy.temperatureFresh(snapshot, nowEpochSeconds)
        val lightningFresh = WidgetSnapshotTimePolicy.lightningFresh(snapshot, nowEpochSeconds)
        val currentTemperature = snapshot.temperatureC.takeIf { temperatureFresh }
        val currentLightning = if (snapshot.lightning == WidgetLightningState.NOT_REQUESTED) {
            WidgetLightningState.NOT_REQUESTED
        } else snapshot.lightning.takeIf { lightningFresh } ?: WidgetLightningState.UNAVAILABLE
        val usable = currentRain != null || currentTemperature != null || lightningFresh
        return snapshot.copy(
            seriesAvailability = if (currentRain == null) RainMinuteAvailability.UNAVAILABLE
                else snapshot.seriesAvailability,
            points = snapshot.points.takeIf { currentRain != null }.orEmpty(),
            temperatureC = currentTemperature,
            temperatureUpdatedEpochSeconds = WidgetSnapshotTimePolicy.temperatureTimestamp(snapshot)
                .takeIf { currentTemperature != null },
            lightning = if (snapshot.lightning == WidgetLightningState.NOT_REQUESTED) {
                WidgetLightningState.NOT_REQUESTED
            } else currentLightning,
            updateUnavailable = snapshot.updateUnavailable || !usable,
        )
    }

    fun hasUsableData(
        snapshot: WidgetWeatherSnapshot,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
    ): Boolean = snapshot.rainSeries(nowEpochSeconds) != null ||
        (snapshot.temperatureC != null &&
            WidgetSnapshotTimePolicy.temperatureFresh(snapshot, nowEpochSeconds)) ||
        WidgetSnapshotTimePolicy.lightningFresh(snapshot, nowEpochSeconds)
}

/** Separates compact storage retention from whether timestamped weather is still truthful now. */
object WidgetSnapshotTimePolicy {
    const val SERIES_START_GRACE_SECONDS = 2 * 60L
    const val TEMPERATURE_FRESH_SECONDS = 90 * 60L
    const val LIGHTNING_FRESH_SECONDS = 20 * 60L

    fun temperatureTimestamp(snapshot: WidgetWeatherSnapshot): Long? =
        snapshot.temperatureUpdatedEpochSeconds ?:
            snapshot.updatedEpochSeconds.takeIf { snapshot.temperatureC != null }

    fun temperatureFresh(snapshot: WidgetWeatherSnapshot, nowEpochSeconds: Long): Boolean =
        temperatureTimestamp(snapshot)?.let {
            nowEpochSeconds - it in 0..TEMPERATURE_FRESH_SECONDS
        } == true

    fun lightningFresh(snapshot: WidgetWeatherSnapshot, nowEpochSeconds: Long): Boolean =
        snapshot.lightningFrameEpochSeconds?.let {
            nowEpochSeconds - it in 0..LIGHTNING_FRESH_SECONDS
        } == true && snapshot.lightning != WidgetLightningState.UNAVAILABLE &&
        snapshot.lightning != WidgetLightningState.NOT_REQUESTED

    fun currentSeries(series: RainMinuteSeries, nowEpochSeconds: Long): RainMinuteSeries? {
        if (series.points.isEmpty()) return null
        val lastMinute = series.points.last().minute
        val end = series.startEpochSeconds + lastMinute * 60L
        if (nowEpochSeconds < series.startEpochSeconds - SERIES_START_GRACE_SECONDS ||
            nowEpochSeconds > end
        ) return null
        val elapsedMinutes = Math.floorDiv(
            (nowEpochSeconds - series.startEpochSeconds).coerceAtLeast(0L),
            60L,
        ).toInt().coerceAtMost(lastMinute)
        if (elapsedMinutes == 0) return series
        val remaining = series.points.filter { it.minute >= elapsedMinutes }.map {
            it.copy(minute = it.minute - elapsedMinutes)
        }
        if (remaining.isEmpty()) return null
        return series.copy(
            startEpochSeconds = nowEpochSeconds,
            points = remaining,
            availability = RainMinuteAvailability.PARTIAL,
        )
    }
}
