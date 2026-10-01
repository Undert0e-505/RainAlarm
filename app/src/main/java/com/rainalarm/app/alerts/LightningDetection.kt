package com.rainalarm.app.alerts

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LightningDetectionPolicy(
    val radiusKilometres: Double = defaultRadiusKilometres,
    val samplingMarginKilometres: Double = defaultSamplingMarginKilometres,
    val imagePixels: Int = defaultImagePixels,
) {
    init {
        require(radiusKilometres > 0.0 && radiusKilometres.isFinite())
        require(samplingMarginKilometres >= 0.0 && samplingMarginKilometres.isFinite())
        require(imagePixels in 64..512)
    }

    companion object {
        const val defaultRadiusKilometres = 15.0
        const val defaultSamplingMarginKilometres = 2.0
        const val defaultImagePixels = 160
        const val configurationVersion = 1
    }
}

data class GeographicBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west.isFinite() && east.isFinite() && south.isFinite() && north.isFinite())
        require(south <= north)
    }

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude !in south..north) return false
        val wrapped = GeoCirclePolicy.wrapLongitude(longitude)
        return if (west <= east) wrapped in west..east else wrapped >= west || wrapped <= east
    }
}

object GeoCirclePolicy {
    private const val EARTH_RADIUS_KILOMETRES = 6_371.0088

    fun wrapLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    fun distanceKilometres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val first = Math.toRadians(lat1)
        val second = Math.toRadians(lat2)
        val deltaLat = second - first
        val deltaLon = Math.toRadians(wrapLongitude(lon2 - lon1))
        val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(first) * cos(second) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
        return 2.0 * EARTH_RADIUS_KILOMETRES * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun circleCovered(
        targetLatitude: Double,
        targetLongitude: Double,
        radiusKilometres: Double,
        coverage: GeographicBounds,
    ): Boolean {
        if (!coverage.contains(targetLatitude, targetLongitude)) return false
        val latDelta = radiusKilometres / 111.32
        val lonScale = cos(Math.toRadians(targetLatitude)).coerceAtLeast(0.01)
        val lonDelta = radiusKilometres / (111.32 * lonScale)
        return coverage.contains(targetLatitude - latDelta, targetLongitude) &&
            coverage.contains(targetLatitude + latDelta, targetLongitude) &&
            coverage.contains(targetLatitude, targetLongitude - lonDelta) &&
            coverage.contains(targetLatitude, targetLongitude + lonDelta)
    }

    /**
     * Conservative full-disc footprint inside the advertised geographic bounds. Geostationary
     * products publish a square envelope around a curved Earth disc; accepting its transparent
     * corners as known clear would be unsafe.
     */
    fun circleCoveredByEllipticalFootprint(
        targetLatitude: Double,
        targetLongitude: Double,
        radiusKilometres: Double,
        coverage: GeographicBounds,
    ): Boolean {
        if (!circleCovered(targetLatitude, targetLongitude, radiusKilometres, coverage) ||
            coverage.west >= coverage.east
        ) return false
        val centreLongitude = (coverage.west + coverage.east) / 2.0
        val centreLatitude = (coverage.south + coverage.north) / 2.0
        val horizontalRadius = (coverage.east - coverage.west) / 2.0
        val verticalRadius = (coverage.north - coverage.south) / 2.0
        if (horizontalRadius <= 0.0 || verticalRadius <= 0.0) return false
        val latitudeDelta = radiusKilometres / 111.32
        val longitudeDelta = radiusKilometres /
            (111.32 * cos(Math.toRadians(targetLatitude)).coerceAtLeast(0.01))
        return listOf(
            targetLatitude - latitudeDelta to targetLongitude,
            targetLatitude + latitudeDelta to targetLongitude,
            targetLatitude to targetLongitude - longitudeDelta,
            targetLatitude to targetLongitude + longitudeDelta,
        ).all { (latitude, longitude) ->
            val x = wrapLongitude(longitude - centreLongitude) / horizontalRadius
            val y = (latitude - centreLatitude) / verticalRadius
            x * x + y * y <= 1.0
        }
    }
}

data class LightningPixelFrame(
    val width: Int,
    val height: Int,
    val alpha: ByteArray,
    val bounds: GeographicBounds,
    val coverage: GeographicBounds,
    val frameEpochSeconds: Long,
) {
    init {
        require(width > 0 && height > 0 && alpha.size == width * height)
    }
}

enum class LightningFrameObservation { DETECTED, NO_DETECTION, UNAVAILABLE }

object LightningPixelDetector {
    const val FLASH_ALPHA_THRESHOLD = 8

    fun evaluate(
        frame: LightningPixelFrame,
        targetLatitude: Double,
        targetLongitude: Double,
        policy: LightningDetectionPolicy,
    ): LightningFrameObservation {
        if (!GeoCirclePolicy.circleCovered(
                targetLatitude, targetLongitude, policy.radiusKilometres, frame.coverage,
            )
        ) return LightningFrameObservation.UNAVAILABLE
        val longitudeSpan = longitudeSpan(frame.bounds.west, frame.bounds.east)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            if ((frame.alpha[y * frame.width + x].toInt() and 0xff) < FLASH_ALPHA_THRESHOLD) continue
            val latitude = frame.bounds.north - (y + 0.5) / frame.height *
                (frame.bounds.north - frame.bounds.south)
            val longitude = GeoCirclePolicy.wrapLongitude(
                frame.bounds.west + (x + 0.5) / frame.width * longitudeSpan,
            )
            if (GeoCirclePolicy.distanceKilometres(
                    targetLatitude, targetLongitude, latitude, longitude,
                ) <= policy.radiusKilometres
            ) return LightningFrameObservation.DETECTED
        }
        return LightningFrameObservation.NO_DETECTION
    }

    private fun longitudeSpan(west: Double, east: Double): Double =
        if (east >= west) east - west else 360.0 - west + east
}

data class LightningEpisodeState(
    val active: Boolean = false,
    val activeEventIdentity: Long = 0L,
)

data class LightningEpisodeTransition(
    val state: LightningEpisodeState,
    val newEventIdentity: Long? = null,
)

object LightningEpisodePolicy {
    fun reduce(
        state: LightningEpisodeState,
        observations: List<Pair<Long, LightningFrameObservation>>,
    ): LightningEpisodeTransition {
        var current = state
        var event: Long? = null
        observations.sortedBy { it.first }.forEach { (identity, observation) ->
            when (observation) {
                LightningFrameObservation.NO_DETECTION ->
                    current = current.copy(active = false, activeEventIdentity = 0L)
                LightningFrameObservation.DETECTED -> {
                    if (!current.active) {
                        event = identity
                        current = current.copy(active = true, activeEventIdentity = identity)
                    }
                }
                LightningFrameObservation.UNAVAILABLE -> Unit
            }
        }
        return LightningEpisodeTransition(current, event)
    }
}

object LightningFrameCatchUpPolicy {
    const val maximumFramesPerTransaction = 12

    fun frames(
        firstAdvertisedEpochSeconds: Long,
        latestAdvertisedEpochSeconds: Long,
        cadenceSeconds: Long,
        lastContiguousEpochSeconds: Long?,
    ): List<Long> {
        require(cadenceSeconds > 0L && firstAdvertisedEpochSeconds <= latestAdvertisedEpochSeconds)
        if (lastContiguousEpochSeconds == null) return listOf(latestAdvertisedEpochSeconds)
        val first = (lastContiguousEpochSeconds + cadenceSeconds).coerceAtLeast(firstAdvertisedEpochSeconds)
        if (first > latestAdvertisedEpochSeconds) return emptyList()
        return generateSequence(first) { it + cadenceSeconds }
            .takeWhile { it <= latestAdvertisedEpochSeconds }
            .take(maximumFramesPerTransaction)
            .toList()
    }
}
