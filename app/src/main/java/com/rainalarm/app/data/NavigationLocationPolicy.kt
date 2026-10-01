package com.rainalarm.app.data

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class LocationRequestMode { OFF, CURRENT, FOLLOW }

data class LocationRequestProfile(
    val mode: LocationRequestMode,
    val intervalMillis: Long,
    val fastestIntervalMillis: Long,
    val maxDelayMillis: Long,
    val initialMaxAgeMillis: Long,
    val waitForAccurateFix: Boolean,
    val requiresFinePermission: Boolean,
    val minimumDistanceMetres: Float,
)

/** One process-wide location request profile is selected from foreground app state. */
object LocationRequestProfiles {
    val Off = LocationRequestProfile(LocationRequestMode.OFF, 0, 0, 0, 0, false, false, 0f)
    val Current = LocationRequestProfile(
        LocationRequestMode.CURRENT,
        intervalMillis = 5_000,
        fastestIntervalMillis = 2_000,
        maxDelayMillis = 0,
        initialMaxAgeMillis = 5_000,
        waitForAccurateFix = true,
        requiresFinePermission = false,
        minimumDistanceMetres = 0f,
    )
    val Follow = LocationRequestProfile(
        LocationRequestMode.FOLLOW,
        // Navigation-style following asks the platform for every genuine fix it can provide.
        // This is a requested cadence rather than a promise: Android and the GNSS hardware may
        // still deliver more slowly, particularly under weak-signal or power constraints.
        intervalMillis = 200,
        fastestIntervalMillis = 200,
        maxDelayMillis = 0,
        initialMaxAgeMillis = 3_000,
        waitForAccurateFix = true,
        requiresFinePermission = true,
        minimumDistanceMetres = 0f,
    )

    fun select(currentSelected: Boolean, foreground: Boolean, followRequested: Boolean): LocationRequestProfile =
        when {
            !currentSelected || !foreground -> Off
            followRequested -> Follow
            else -> Current
        }
}

enum class LocationFixSource { FUSED, GPS, NETWORK }
enum class LocationFixQuality { ACCURATE, WEAK, PROVISIONAL, APPROXIMATE }

/** Android-free fix model used to arbitrate fused and framework callbacks deterministically. */
data class NavigationLocationFix(
    val latitude: Double,
    val longitude: Double,
    val wallTimeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMetres: Float,
    val source: LocationFixSource,
    val finePermission: Boolean,
    val speedMetresPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val speedAccuracyMetresPerSecond: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
)

enum class TravelVisualMotionSource { STATIONARY, INTERPOLATED, PLATFORM, DERIVED }

/**
 * A visual-only Travel target. It is deliberately not a [SavedPlace] or a
 * [NavigationLocationFix], so projected coordinates cannot become weather, alert, persistence or
 * selection inputs by accident.
 */
data class TravelDisplayTarget(
    val latitude: Double,
    val longitude: Double,
    val sourceFixElapsedRealtimeNanos: Long,
    val displayElapsedRealtimeNanos: Long,
    val source: LocationFixSource,
    val projected: Boolean,
    val motionSource: TravelVisualMotionSource,
)

internal object TravelVisualMotionPolicy {
    const val tickMillis = 200L
    const val maximumProjectionMillis = 1_200L
    const val reconciliationMillis = 600L
    const val defaultInterpolationMillis = 800L
    const val minimumInterpolationMillis = 200L
    const val maximumInterpolationMillis = 1_000L
    const val maximumInterpolationCadenceMillis = 3_000L
    const val maximumInterpolationDistanceMetres = 150.0
    const val maximumAccuracyMetres = 50f
    const val stationarySpeedMetresPerSecond = 0.75
    const val maximumSpeedMetresPerSecond = 70.0
    const val maximumAccelerationMetresPerSecondSquared = 12.0
    const val maximumCorrectionMetres = 35.0
    const val minimumPublishedMovementMetres = 0.05
    private const val maximumDerivedIntervalSeconds = 2.5

    fun usableForProjection(fix: NavigationLocationFix): Boolean =
        fix.finePermission && fix.source != LocationFixSource.NETWORK &&
            fix.accuracyMetres.isFinite() && fix.accuracyMetres <= maximumAccuracyMetres

    fun crediblyStationary(fix: NavigationLocationFix): Boolean {
        val speed = fix.speedMetresPerSecond ?: return false
        if (!speed.isFinite() || speed >= stationarySpeedMetresPerSecond) return false
        return fix.speedAccuracyMetresPerSecond?.let {
            it.isFinite() && it <= 2.5f
        } ?: true
    }

    fun shouldPublish(previous: TravelDisplayTarget?, candidate: TravelDisplayTarget): Boolean =
        previous == null || distanceMetres(
            previous.latitude,
            previous.longitude,
            candidate.latitude,
            candidate.longitude,
        ) >= minimumPublishedMovementMetres

    fun delayUntilNextTickMillis(nowElapsedNanos: Long): Long {
        val periodNanos = tickMillis * 1_000_000L
        val remainder = Math.floorMod(nowElapsedNanos, periodNanos)
        return ((periodNanos - remainder) / 1_000_000L).coerceAtLeast(1L)
    }

    fun projectionSeconds(fixElapsedNanos: Long, nowElapsedNanos: Long): Double {
        if (fixElapsedNanos <= 0L || nowElapsedNanos <= fixElapsedNanos) return 0.0
        return min(
            (nowElapsedNanos - fixElapsedNanos) / 1_000_000_000.0,
            maximumProjectionMillis / 1_000.0,
        )
    }

    fun interpolationDurationMillis(
        previous: NavigationLocationFix?,
        current: NavigationLocationFix,
    ): Long {
        val cadenceMillis = previous?.let {
            when {
                current.elapsedRealtimeNanos > it.elapsedRealtimeNanos &&
                    it.elapsedRealtimeNanos > 0L ->
                    (current.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000L
                current.wallTimeMillis > it.wallTimeMillis ->
                    current.wallTimeMillis - it.wallTimeMillis
                else -> null
            }
        } ?: return defaultInterpolationMillis
        if (cadenceMillis > maximumInterpolationCadenceMillis) return 0L
        return (cadenceMillis * 85L / 100L).coerceIn(
            minimumInterpolationMillis,
            maximumInterpolationMillis,
        )
    }

    fun derivedVelocity(
        previous: NavigationLocationFix,
        current: NavigationLocationFix,
    ): Pair<Double, Double>? {
        if (!usableForProjection(previous) || !usableForProjection(current)) return null
        val elapsedSeconds = when {
            current.elapsedRealtimeNanos > previous.elapsedRealtimeNanos &&
                previous.elapsedRealtimeNanos > 0L ->
                (current.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
            else -> (current.wallTimeMillis - previous.wallTimeMillis) / 1_000.0
        }
        if (elapsedSeconds <= 0.0 || elapsedSeconds > maximumDerivedIntervalSeconds) return null
        val distance = distanceMetres(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        val jitterFloor = max(
            2.0,
            min(10.0, max(previous.accuracyMetres, current.accuracyMetres) * 0.5),
        )
        if (distance < jitterFloor) return null
        val speed = distance / elapsedSeconds
        if (speed !in stationarySpeedMetresPerSecond..maximumSpeedMetresPerSecond) return null
        return speed to initialBearingDegrees(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
    }

    private fun initialBearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val fromLat = Math.toRadians(fromLatitude)
        val toLat = Math.toRadians(toLatitude)
        val deltaLon = Math.toRadians(toLongitude - fromLongitude)
        val y = sin(deltaLon) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

/**
 * Visual-only Travel smoothing. Reliable motion can use bounded dead reckoning; otherwise sparse
 * genuine fixes are approached causally so presentation remains smooth without inventing a future
 * position. Real fixes remain separately owned by the location and weather pipeline.
 */
internal class TravelVisualMotionTracker {
    private data class Motion(
        val speedMetresPerSecond: Double,
        val bearingDegrees: Double,
        val source: TravelVisualMotionSource,
    )

    private var latestFix: NavigationLocationFix? = null
    private var motion: Motion? = null
    private var baseLatitude: Double? = null
    private var baseLongitude: Double? = null
    private var correctionLatitudeDegrees = 0.0
    private var correctionLongitudeDegrees = 0.0
    private var correctionStartedElapsedNanos = 0L
    private var interpolationStartLatitude: Double? = null
    private var interpolationStartLongitude: Double? = null
    private var interpolationEndLatitude: Double? = null
    private var interpolationEndLongitude: Double? = null
    private var interpolationStartedElapsedNanos = 0L
    private var interpolationDurationMillis = 0L

    fun clear() {
        latestFix = null
        motion = null
        baseLatitude = null
        baseLongitude = null
        correctionLatitudeDegrees = 0.0
        correctionLongitudeDegrees = 0.0
        correctionStartedElapsedNanos = 0L
        clearInterpolation()
    }

    fun accept(
        fix: NavigationLocationFix,
        receivedElapsedNanos: Long = fix.elapsedRealtimeNanos,
    ): TravelDisplayTarget? {
        val before = target(receivedElapsedNanos)
        val priorFix = latestFix
        val selectedMotion = selectMotion(priorFix, fix)
        val usable = TravelVisualMotionPolicy.usableForProjection(fix)
        val rawDistance = priorFix?.let {
            distanceMetres(it.latitude, it.longitude, fix.latitude, fix.longitude)
        }
        val jitterRadius = priorFix?.let {
            max(3.0, min(12.0, max(it.accuracyMetres, fix.accuracyMetres).toDouble()))
        } ?: 0.0
        val holdStationary = usable && before != null &&
            TravelVisualMotionPolicy.crediblyStationary(fix) &&
            rawDistance != null && rawDistance <= jitterRadius

        latestFix = fix
        motion = if (usable) selectedMotion else null
        if (!usable || holdStationary) {
            clearInterpolation()
            baseLatitude = before?.latitude ?: fix.latitude
            baseLongitude = before?.longitude ?: fix.longitude
            correctionLatitudeDegrees = 0.0
            correctionLongitudeDegrees = 0.0
            correctionStartedElapsedNanos = receivedElapsedNanos
        } else if (before != null &&
            (selectedMotion == null || selectedMotion.source == TravelVisualMotionSource.STATIONARY) &&
            rawDistance != null
        ) {
            startCausalInterpolation(before, fix, priorFix, rawDistance, receivedElapsedNanos)
        } else {
            clearInterpolation()
            baseLatitude = fix.latitude
            baseLongitude = fix.longitude
            val correctionDistance = before?.let {
                distanceMetres(it.latitude, it.longitude, fix.latitude, fix.longitude)
            }
            if (before != null && correctionDistance != null &&
                correctionDistance <= TravelVisualMotionPolicy.maximumCorrectionMetres
            ) {
                correctionLatitudeDegrees = before.latitude - fix.latitude
                correctionLongitudeDegrees = shortestLongitudeDelta(before.longitude, fix.longitude)
            } else {
                correctionLatitudeDegrees = 0.0
                correctionLongitudeDegrees = 0.0
            }
            correctionStartedElapsedNanos = receivedElapsedNanos
        }
        return target(receivedElapsedNanos)
    }

    fun target(nowElapsedNanos: Long): TravelDisplayTarget? {
        val fix = latestFix ?: return null
        interpolationTarget(fix, nowElapsedNanos)?.let { return it }
        val startLatitude = baseLatitude ?: fix.latitude
        val startLongitude = baseLongitude ?: fix.longitude
        val activeMotion = motion
        val projected = if (activeMotion != null &&
            activeMotion.speedMetresPerSecond >= TravelVisualMotionPolicy.stationarySpeedMetresPerSecond
        ) {
            val seconds = TravelVisualMotionPolicy.projectionSeconds(
                fix.elapsedRealtimeNanos,
                nowElapsedNanos,
            )
            destination(
                startLatitude,
                startLongitude,
                activeMotion.speedMetresPerSecond * seconds,
                activeMotion.bearingDegrees,
            )
        } else startLatitude to startLongitude
        val correctionAgeMillis = if (correctionStartedElapsedNanos > 0L &&
            nowElapsedNanos > correctionStartedElapsedNanos
        ) (nowElapsedNanos - correctionStartedElapsedNanos) / 1_000_000.0 else 0.0
        val correctionFraction = (1.0 -
            correctionAgeMillis / TravelVisualMotionPolicy.reconciliationMillis).coerceIn(0.0, 1.0)
        val latitude = (projected.first + correctionLatitudeDegrees * correctionFraction)
            .coerceIn(-90.0, 90.0)
        val longitude = normalizeLongitude(
            projected.second + correctionLongitudeDegrees * correctionFraction,
        )
        val isProjected = activeMotion?.speedMetresPerSecond?.let {
            it >= TravelVisualMotionPolicy.stationarySpeedMetresPerSecond &&
                nowElapsedNanos > fix.elapsedRealtimeNanos
        } == true || correctionFraction > 0.0 &&
            (correctionLatitudeDegrees != 0.0 || correctionLongitudeDegrees != 0.0)
        return TravelDisplayTarget(
            latitude = latitude,
            longitude = longitude,
            sourceFixElapsedRealtimeNanos = fix.elapsedRealtimeNanos,
            displayElapsedRealtimeNanos = nowElapsedNanos,
            source = fix.source,
            projected = isProjected,
            motionSource = activeMotion?.source ?: TravelVisualMotionSource.STATIONARY,
        )
    }

    private fun startCausalInterpolation(
        before: TravelDisplayTarget,
        fix: NavigationLocationFix,
        previous: NavigationLocationFix?,
        distanceMetres: Double,
        receivedElapsedNanos: Long,
    ) {
        val duration = TravelVisualMotionPolicy.interpolationDurationMillis(previous, fix)
        val safeToInterpolate = duration > 0L &&
            distanceMetres <= TravelVisualMotionPolicy.maximumInterpolationDistanceMetres
        motion = null
        correctionLatitudeDegrees = 0.0
        correctionLongitudeDegrees = 0.0
        correctionStartedElapsedNanos = receivedElapsedNanos
        if (!safeToInterpolate) {
            clearInterpolation()
            baseLatitude = fix.latitude
            baseLongitude = fix.longitude
            return
        }
        baseLatitude = fix.latitude
        baseLongitude = fix.longitude
        interpolationStartLatitude = before.latitude
        interpolationStartLongitude = before.longitude
        interpolationEndLatitude = fix.latitude
        interpolationEndLongitude = fix.longitude
        interpolationStartedElapsedNanos = receivedElapsedNanos
        interpolationDurationMillis = duration
    }

    private fun interpolationTarget(
        fix: NavigationLocationFix,
        nowElapsedNanos: Long,
    ): TravelDisplayTarget? {
        val startLatitude = interpolationStartLatitude ?: return null
        val startLongitude = interpolationStartLongitude ?: return null
        val endLatitude = interpolationEndLatitude ?: return null
        val endLongitude = interpolationEndLongitude ?: return null
        val duration = interpolationDurationMillis.takeIf { it > 0L } ?: return null
        val ageMillis = if (nowElapsedNanos > interpolationStartedElapsedNanos) {
            (nowElapsedNanos - interpolationStartedElapsedNanos) / 1_000_000.0
        } else 0.0
        val fraction = (ageMillis / duration).coerceIn(0.0, 1.0)
        val longitudeDelta = normalizeLongitude(endLongitude - startLongitude)
        return TravelDisplayTarget(
            latitude = (startLatitude + (endLatitude - startLatitude) * fraction)
                .coerceIn(-90.0, 90.0),
            longitude = normalizeLongitude(startLongitude + longitudeDelta * fraction),
            sourceFixElapsedRealtimeNanos = fix.elapsedRealtimeNanos,
            displayElapsedRealtimeNanos = nowElapsedNanos,
            source = fix.source,
            projected = false,
            motionSource = if (fraction < 1.0) {
                TravelVisualMotionSource.INTERPOLATED
            } else {
                TravelVisualMotionSource.STATIONARY
            },
        )
    }

    private fun clearInterpolation() {
        interpolationStartLatitude = null
        interpolationStartLongitude = null
        interpolationEndLatitude = null
        interpolationEndLongitude = null
        interpolationStartedElapsedNanos = 0L
        interpolationDurationMillis = 0L
    }

    private fun selectMotion(
        previous: NavigationLocationFix?,
        current: NavigationLocationFix,
    ): Motion? {
        if (!TravelVisualMotionPolicy.usableForProjection(current)) return null
        val platformSpeed = current.speedMetresPerSecond?.toDouble()?.takeIf {
            it.isFinite() && it in 0.0..TravelVisualMotionPolicy.maximumSpeedMetresPerSecond
        }
        if (platformSpeed != null && platformSpeed < TravelVisualMotionPolicy.stationarySpeedMetresPerSecond) {
            return Motion(0.0, current.bearingDegrees?.toDouble() ?: 0.0,
                TravelVisualMotionSource.STATIONARY)
        }
        val platformBearing = current.bearingDegrees?.toDouble()?.takeIf { it.isFinite() }
        val speedAccurate = current.speedAccuracyMetresPerSecond?.let {
            it.isFinite() && platformSpeed != null && it <= max(2.5, platformSpeed * 0.6)
        } ?: true
        val bearingAccurate = current.bearingAccuracyDegrees?.let {
            it.isFinite() && it <= 45f
        } ?: true
        val raw = if (platformSpeed != null && platformBearing != null &&
            speedAccurate && bearingAccurate
        ) {
            Motion(platformSpeed, normalizeBearing(platformBearing), TravelVisualMotionSource.PLATFORM)
        } else previous?.let { TravelVisualMotionPolicy.derivedVelocity(it, current) }
            ?.let { Motion(it.first, it.second, TravelVisualMotionSource.DERIVED) }
            ?: return Motion(0.0, 0.0, TravelVisualMotionSource.STATIONARY)

        val old = motion
        val elapsedSeconds = previous?.let {
            if (current.elapsedRealtimeNanos > it.elapsedRealtimeNanos && it.elapsedRealtimeNanos > 0L) {
                (current.elapsedRealtimeNanos - it.elapsedRealtimeNanos) / 1_000_000_000.0
            } else (current.wallTimeMillis - it.wallTimeMillis) / 1_000.0
        }?.coerceAtLeast(0.0) ?: 0.0
        if (old == null || elapsedSeconds <= 0.0) return raw
        val maximumChange = TravelVisualMotionPolicy.maximumAccelerationMetresPerSecondSquared *
            elapsedSeconds.coerceAtMost(TravelVisualMotionPolicy.maximumProjectionMillis / 1_000.0)
        return raw.copy(
            speedMetresPerSecond = raw.speedMetresPerSecond.coerceIn(
                (old.speedMetresPerSecond - maximumChange).coerceAtLeast(0.0),
                (old.speedMetresPerSecond + maximumChange)
                    .coerceAtMost(TravelVisualMotionPolicy.maximumSpeedMetresPerSecond),
            ),
        )
    }

    private fun destination(
        latitude: Double,
        longitude: Double,
        distanceMetres: Double,
        bearingDegrees: Double,
    ): Pair<Double, Double> {
        if (distanceMetres <= 0.0) return latitude to longitude
        val angularDistance = distanceMetres / 6_371_000.0
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val lat2 = asin(
            sin(lat1) * cos(angularDistance) +
                cos(lat1) * sin(angularDistance) * cos(bearing),
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2),
        )
        return Math.toDegrees(lat2) to normalizeLongitude(Math.toDegrees(lon2))
    }

    private fun shortestLongitudeDelta(from: Double, to: Double): Double =
        normalizeLongitude(from - to)

    private fun normalizeBearing(value: Double): Double = (value % 360.0 + 360.0) % 360.0

    private fun normalizeLongitude(value: Double): Double =
        (value + 540.0) % 360.0 - 180.0
}

sealed interface LocationFixDecision {
    data class Accept(val fix: NavigationLocationFix, val quality: LocationFixQuality) : LocationFixDecision
    data class HoldPrevious(val reason: String) : LocationFixDecision
    data class Reject(val reason: String) : LocationFixDecision
}

object NavigationFixAdjudicationPolicy {
    const val maximumCandidateAgeMillis = 2 * 60_000L
    const val accurateThresholdMetres = 50f
    const val weakThresholdMetres = 250f
    const val weakSignalGraceMillis = 15_000L
    const val maximumPlausibleSpeedMetresPerSecond = 80.0
    private const val jumpSlackMetres = 75.0

    fun quality(fix: NavigationLocationFix): LocationFixQuality = when {
        !fix.finePermission -> LocationFixQuality.APPROXIMATE
        fix.source == LocationFixSource.NETWORK -> LocationFixQuality.PROVISIONAL
        fix.accuracyMetres <= accurateThresholdMetres -> LocationFixQuality.ACCURATE
        fix.accuracyMetres <= weakThresholdMetres -> LocationFixQuality.WEAK
        else -> LocationFixQuality.PROVISIONAL
    }

    fun decide(
        candidate: NavigationLocationFix,
        previous: NavigationLocationFix?,
        nowWallMillis: Long,
        nowElapsedNanos: Long,
    ): LocationFixDecision {
        if (!candidate.latitude.isFinite() || !candidate.longitude.isFinite() ||
            candidate.latitude !in -90.0..90.0 || candidate.longitude !in -180.0..180.0 ||
            !candidate.accuracyMetres.isFinite() || candidate.accuracyMetres < 0f ||
            candidate.wallTimeMillis <= 0L || nowWallMillis - candidate.wallTimeMillis !in 0..maximumCandidateAgeMillis
        ) return LocationFixDecision.Reject("invalid or stale fix")
        if (previous == null) return LocationFixDecision.Accept(candidate, quality(candidate))

        val ordered = if (candidate.elapsedRealtimeNanos > 0L && previous.elapsedRealtimeNanos > 0L) {
            candidate.elapsedRealtimeNanos > previous.elapsedRealtimeNanos
        } else candidate.wallTimeMillis > previous.wallTimeMillis
        if (!ordered) return LocationFixDecision.Reject("out-of-order fix")

        val previousAgeMillis = when {
            previous.elapsedRealtimeNanos > 0L && nowElapsedNanos >= previous.elapsedRealtimeNanos ->
                (nowElapsedNanos - previous.elapsedRealtimeNanos) / 1_000_000L
            else -> nowWallMillis - previous.wallTimeMillis
        }
        val previousStale = previousAgeMillis > maximumCandidateAgeMillis
        val substantiallyCoarser = candidate.accuracyMetres > max(
            previous.accuracyMetres * 3f,
            previous.accuracyMetres + 50f,
        )
        if (!previousStale && previousAgeMillis <= weakSignalGraceMillis &&
            quality(previous) == LocationFixQuality.ACCURATE && substantiallyCoarser
        ) return LocationFixDecision.HoldPrevious("recent accurate fix retained")

        if (!previousStale) {
            val elapsedSeconds = when {
                candidate.elapsedRealtimeNanos > previous.elapsedRealtimeNanos ->
                    (candidate.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0
                else -> (candidate.wallTimeMillis - previous.wallTimeMillis) / 1_000.0
            }.coerceAtLeast(0.001)
            val allowance = previous.accuracyMetres + candidate.accuracyMetres + jumpSlackMetres +
                maximumPlausibleSpeedMetresPerSecond * elapsedSeconds
            if (distanceMetres(previous.latitude, previous.longitude, candidate.latitude, candidate.longitude) > allowance)
                return LocationFixDecision.Reject("implausible location jump")
        }
        return LocationFixDecision.Accept(candidate, quality(candidate))
    }

    fun withinWeakSignalGrace(fix: NavigationLocationFix, nowElapsedNanos: Long): Boolean =
        fix.elapsedRealtimeNanos > 0L && nowElapsedNanos >= fix.elapsedRealtimeNanos &&
            (nowElapsedNanos - fix.elapsedRealtimeNanos) / 1_000_000L <= weakSignalGraceMillis
}

enum class FollowCapabilityReason { PRECISE_PERMISSION, WAITING_FOR_FIX, WEAK_ACCURACY }

object FollowCapabilityPolicy {
    fun canFollow(finePermission: Boolean, quality: LocationFixQuality?): Boolean =
        finePermission && quality == LocationFixQuality.ACCURATE

    fun unavailableReason(
        finePermission: Boolean,
        quality: LocationFixQuality?,
    ): FollowCapabilityReason = when {
        !finePermission -> FollowCapabilityReason.PRECISE_PERMISSION
        quality == null -> FollowCapabilityReason.WAITING_FOR_FIX
        else -> FollowCapabilityReason.WEAK_ACCURACY
    }
}

/** Separates frequent live map movement from deliberately slower weather/network anchoring. */
object WeatherAnalysisAnchorPolicy {
    const val followDistanceMetres = 1_000.0
    const val followTimedDistanceMetres = 250.0
    const val followElapsedMillis = 2 * 60_000L
    const val ordinaryDistanceMetres = 250.0

    fun shouldAdvance(
        previous: NavigationLocationFix?,
        candidate: NavigationLocationFix,
        following: Boolean,
        previousRegion: String?,
        candidateRegion: String?,
    ): Boolean {
        if (previous == null) return true
        if (previousRegion != candidateRegion) return true
        val distance = distanceMetres(
            previous.latitude, previous.longitude, candidate.latitude, candidate.longitude,
        )
        if (!following) return distance >= ordinaryDistanceMetres
        val elapsedMillis = when {
            candidate.elapsedRealtimeNanos > previous.elapsedRealtimeNanos && previous.elapsedRealtimeNanos > 0L ->
                (candidate.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
            else -> candidate.wallTimeMillis - previous.wallTimeMillis
        }
        return distance >= followDistanceMetres ||
            (elapsedMillis >= followElapsedMillis && distance >= followTimedDistanceMetres)
    }
}

fun distanceMetres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
        sin(dLon / 2).pow(2)
    return 2 * radius * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
