package com.rainalarm.app.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
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
)

/** One process-wide location request profile is selected from foreground app state. */
object LocationRequestProfiles {
    val Off = LocationRequestProfile(LocationRequestMode.OFF, 0, 0, 0, 0, false, false)
    val Current = LocationRequestProfile(
        LocationRequestMode.CURRENT,
        intervalMillis = 5_000,
        fastestIntervalMillis = 2_000,
        maxDelayMillis = 0,
        initialMaxAgeMillis = 5_000,
        waitForAccurateFix = true,
        requiresFinePermission = false,
    )
    val Follow = LocationRequestProfile(
        LocationRequestMode.FOLLOW,
        intervalMillis = 1_000,
        fastestIntervalMillis = 500,
        maxDelayMillis = 0,
        initialMaxAgeMillis = 3_000,
        waitForAccurateFix = true,
        requiresFinePermission = true,
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
)

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

object FollowCapabilityPolicy {
    fun canFollow(finePermission: Boolean, quality: LocationFixQuality?): Boolean =
        finePermission && quality == LocationFixQuality.ACCURATE

    fun unavailableMessage(finePermission: Boolean, quality: LocationFixQuality?): String = when {
        !finePermission -> "Precise location is required for Follow. Enable precise location in Android settings."
        quality == null -> "Wait for an accurate location fix before starting Follow."
        else -> "Location accuracy is too weak for Follow. Move to a clearer view of the sky and try again."
    }
}

/** Separates one-second map movement from deliberately slower weather/network anchoring. */
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
