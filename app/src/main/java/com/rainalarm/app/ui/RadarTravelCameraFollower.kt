package com.rainalarm.app.ui

import android.view.Choreographer
import com.rainalarm.app.data.distanceMetres
import kotlin.math.sqrt

internal enum class RadarTravelCameraTargetAction { STARTED, RETARGETED, RESET, IGNORED }

internal enum class RadarTravelCameraResumeAction { NONE, SCHEDULE, APPLY_CURRENT }

/**
 * Pure interaction gate for the native follower. Location targets continue to update while a
 * pointer owns the map, but no camera mutation may escape until an unclaimed pointer finishes.
 */
internal class RadarTravelCameraGestureGate {
    var paused: Boolean = false
        private set
    private var targetAcceptedWhilePaused = false

    fun pause(): Boolean {
        if (paused) return false
        paused = true
        targetAcceptedWhilePaused = false
        return true
    }

    fun targetAccepted() {
        if (paused) targetAcceptedWhilePaused = true
    }

    fun resume(enabled: Boolean, settled: Boolean): RadarTravelCameraResumeAction {
        if (!enabled || !paused) return RadarTravelCameraResumeAction.NONE
        paused = false
        return when {
            !settled -> RadarTravelCameraResumeAction.SCHEDULE
            targetAcceptedWhilePaused -> RadarTravelCameraResumeAction.APPLY_CURRENT
            else -> RadarTravelCameraResumeAction.NONE
        }.also { targetAcceptedWhilePaused = false }
    }

    fun reset() {
        paused = false
        targetAcceptedWhilePaused = false
    }

    fun allowsCameraMutation(enabled: Boolean): Boolean = enabled && !paused
}

/** Keeps viewport-derived work bounded while the Travel camera itself updates at display cadence. */
internal object RadarTravelViewportPolicy {
    const val PUBLISH_INTERVAL_NANOS = 1_000_000_000L

    fun shouldPublish(
        following: Boolean,
        nowElapsedNanos: Long,
        lastPublishedElapsedNanos: Long,
    ): Boolean = !following || lastPublishedElapsedNanos <= 0L ||
        nowElapsedNanos - lastPublishedElapsedNanos >= PUBLISH_INTERVAL_NANOS
}

/** Coordinate-free sampling keeps debug evidence useful without logging on every display frame. */
internal class RadarTravelFrameDiagnosticSampler(
    private val intervalNanos: Long = 1_000_000_000L,
) {
    private var lastPublishedNanos = 0L
    private var framesSincePublication = 0

    init {
        require(intervalNanos > 0L)
    }

    fun record(frameTimeNanos: Long): Int? {
        if (frameTimeNanos <= 0L) return null
        framesSincePublication++
        if (lastPublishedNanos == 0L) {
            lastPublishedNanos = frameTimeNanos
            return null
        }
        if (frameTimeNanos - lastPublishedNanos < intervalNanos) return null
        lastPublishedNanos = frameTimeNanos
        return framesSincePublication.also { framesSincePublication = 0 }
    }
}

/**
 * Coordinate-only, monotonic-time trajectory used by the native Travel camera follower.
 *
 * It is a critically damped pursuit of the newest safe presentation target. Position and velocity
 * survive a mid-flight retarget, while an overshoot guard ensures fallback interpolation never
 * invents a point beyond that target. Frame stalls are deliberately bounded instead of becoming a
 * large visible jump.
 */
internal class RadarTravelCameraTrajectory(
    private val smoothingSeconds: Double = 0.11,
    private val maximumFrameSeconds: Double = 0.05,
    private val teleportDistanceMetres: Double = 250.0,
) {
    var latitude: Double = Double.NaN
        private set
    var longitude: Double = Double.NaN
        private set
    var latitudeVelocity: Double = 0.0
        private set
    var longitudeVelocity: Double = 0.0
        private set
    var active: Boolean = false
        private set
    var settled: Boolean = true
        private set

    private var targetLatitude = Double.NaN
    private var targetLongitude = Double.NaN
    private var lastFrameNanos = 0L
    private var lastTargetElapsedNanos = 0L

    init {
        require(smoothingSeconds > 0.0 && smoothingSeconds.isFinite())
        require(maximumFrameSeconds > 0.0 && maximumFrameSeconds.isFinite())
        require(teleportDistanceMetres > 0.0 && teleportDistanceMetres.isFinite())
    }

    fun start(
        latitude: Double,
        longitude: Double,
        frameTimeNanos: Long,
    ): Boolean {
        if (!valid(latitude, longitude)) return false
        this.latitude = latitude
        this.longitude = normalizeLongitude(longitude)
        targetLatitude = latitude
        targetLongitude = this.longitude
        latitudeVelocity = 0.0
        longitudeVelocity = 0.0
        lastFrameNanos = frameTimeNanos.coerceAtLeast(0L)
        lastTargetElapsedNanos = 0L
        settled = true
        active = true
        return true
    }

    fun acceptTarget(
        latitude: Double,
        longitude: Double,
        targetElapsedNanos: Long,
    ): RadarTravelCameraTargetAction {
        if (!valid(latitude, longitude)) return RadarTravelCameraTargetAction.IGNORED
        if (!active) {
            start(latitude, longitude, targetElapsedNanos)
            lastTargetElapsedNanos = targetElapsedNanos.coerceAtLeast(0L)
            return RadarTravelCameraTargetAction.STARTED
        }
        if (targetElapsedNanos > 0L && lastTargetElapsedNanos > 0L &&
            targetElapsedNanos <= lastTargetElapsedNanos
        ) return RadarTravelCameraTargetAction.IGNORED

        lastTargetElapsedNanos = targetElapsedNanos.coerceAtLeast(lastTargetElapsedNanos)
        val normalized = normalizeLongitude(longitude)
        val distance = distanceMetres(this.latitude, normalizeLongitude(this.longitude), latitude, normalized)
        targetLatitude = latitude
        targetLongitude = unwrapLongitude(normalized, this.longitude)
        if (distance > teleportDistanceMetres) {
            this.latitude = targetLatitude
            this.longitude = targetLongitude
            latitudeVelocity = 0.0
            longitudeVelocity = 0.0
            settled = true
            return RadarTravelCameraTargetAction.RESET
        }
        settled = distance <= SETTLED_DISTANCE_METRES
        if (settled) {
            this.latitude = targetLatitude
            this.longitude = targetLongitude
            latitudeVelocity = 0.0
            longitudeVelocity = 0.0
        }
        return RadarTravelCameraTargetAction.RETARGETED
    }

    /** Returns true only when the native camera should consume the current coordinate. */
    fun step(frameTimeNanos: Long): Boolean {
        if (!active || settled || frameTimeNanos <= 0L) return false
        if (lastFrameNanos <= 0L || frameTimeNanos <= lastFrameNanos) {
            lastFrameNanos = frameTimeNanos
            return false
        }
        val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0)
            .coerceIn(MINIMUM_FRAME_SECONDS, maximumFrameSeconds)
        lastFrameNanos = frameTimeNanos

        val omega = 2.0 / smoothingSeconds
        val x = omega * dt
        val decay = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x)
        val changeLatitude = latitude - targetLatitude
        val changeLongitude = longitude - targetLongitude
        val tempLatitude = (latitudeVelocity + omega * changeLatitude) * dt
        val tempLongitude = (longitudeVelocity + omega * changeLongitude) * dt
        var nextLatitudeVelocity = (latitudeVelocity - omega * tempLatitude) * decay
        var nextLongitudeVelocity = (longitudeVelocity - omega * tempLongitude) * decay
        var nextLatitude = targetLatitude + (changeLatitude + tempLatitude) * decay
        var nextLongitude = targetLongitude + (changeLongitude + tempLongitude) * decay

        // A critically damped scalar normally cannot overshoot; retain a vector guard for a target
        // that reverses while a prior velocity is still active.
        val toTargetLatitude = targetLatitude - latitude
        val toTargetLongitude = targetLongitude - longitude
        val beyondLatitude = nextLatitude - targetLatitude
        val beyondLongitude = nextLongitude - targetLongitude
        if (toTargetLatitude * beyondLatitude + toTargetLongitude * beyondLongitude > 0.0) {
            nextLatitude = targetLatitude
            nextLongitude = targetLongitude
            nextLatitudeVelocity = 0.0
            nextLongitudeVelocity = 0.0
        }

        latitude = nextLatitude.coerceIn(-MAXIMUM_LATITUDE, MAXIMUM_LATITUDE)
        longitude = nextLongitude
        latitudeVelocity = nextLatitudeVelocity
        longitudeVelocity = nextLongitudeVelocity
        val remaining = distanceMetres(
            latitude,
            normalizeLongitude(longitude),
            targetLatitude,
            normalizeLongitude(targetLongitude),
        )
        val speedMetresPerSecond = sqrt(
            latitudeVelocity * latitudeVelocity + longitudeVelocity * longitudeVelocity,
        ) * METRES_PER_DEGREE
        if (remaining <= SETTLED_DISTANCE_METRES && speedMetresPerSecond <= SETTLED_SPEED_METRES_PER_SECOND) {
            latitude = targetLatitude
            longitude = targetLongitude
            latitudeVelocity = 0.0
            longitudeVelocity = 0.0
            settled = true
        }
        return true
    }

    fun stop() {
        active = false
        settled = true
        latitudeVelocity = 0.0
        longitudeVelocity = 0.0
        lastFrameNanos = 0L
        lastTargetElapsedNanos = 0L
    }

    fun normalizedLongitude(): Double = normalizeLongitude(longitude)

    private fun valid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -MAXIMUM_LATITUDE..MAXIMUM_LATITUDE && longitude in -180.0..180.0

    private fun unwrapLongitude(value: Double, reference: Double): Double {
        var result = value
        while (result - reference > 180.0) result -= 360.0
        while (result - reference < -180.0) result += 360.0
        return result
    }

    private fun normalizeLongitude(value: Double): Double {
        if (!value.isFinite()) return value
        var result = value % 360.0
        if (result > 180.0) result -= 360.0
        if (result < -180.0) result += 360.0
        return result
    }

    private companion object {
        const val MAXIMUM_LATITUDE = 85.05112878
        const val METRES_PER_DEGREE = 111_320.0
        const val MINIMUM_FRAME_SECONDS = 1.0 / 240.0
        const val SETTLED_DISTANCE_METRES = 0.02
        const val SETTLED_SPEED_METRES_PER_SECOND = 0.02
    }
}

/** Main-thread owner of exactly one vsync callback for the retained native MapView. */
internal class RadarTravelCameraFollower(
    private val choreographer: Choreographer,
    private val applyCamera: (latitude: Double, longitude: Double, frameTimeNanos: Long) -> Unit,
) {
    private val trajectory = RadarTravelCameraTrajectory()
    private val gestureGate = RadarTravelCameraGestureGate()
    private var callbackPosted = false
    private var enabled = false
    private val frameCallback = Choreographer.FrameCallback(::onFrame)

    val isRunning: Boolean get() = enabled

    fun start(latitude: Double, longitude: Double, frameTimeNanos: Long) {
        enabled = trajectory.start(latitude, longitude, frameTimeNanos)
    }

    fun submit(
        latitude: Double,
        longitude: Double,
        targetElapsedNanos: Long,
    ): RadarTravelCameraTargetAction {
        val action = trajectory.acceptTarget(latitude, longitude, targetElapsedNanos)
        if (action != RadarTravelCameraTargetAction.IGNORED) {
            enabled = true
            gestureGate.targetAccepted()
            if (action == RadarTravelCameraTargetAction.RESET &&
                gestureGate.allowsCameraMutation(enabled)
            ) {
                applyCamera(trajectory.latitude, trajectory.normalizedLongitude(), targetElapsedNanos)
            } else if (!trajectory.settled && gestureGate.allowsCameraMutation(enabled)) schedule()
        }
        return action
    }

    /** Stops the vsync writer before MapLibre attempts to recognise a pan or pinch. */
    fun pauseForGesture() {
        if (!enabled || !gestureGate.pause()) return
        if (callbackPosted) choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
    }

    /** Resumes only an unclaimed touch, using the latest target received during that touch. */
    fun resumeAfterUnclaimedGesture(frameTimeNanos: Long) {
        when (gestureGate.resume(enabled, trajectory.settled)) {
            RadarTravelCameraResumeAction.NONE -> Unit
            RadarTravelCameraResumeAction.SCHEDULE -> schedule()
            RadarTravelCameraResumeAction.APPLY_CURRENT -> applyCamera(
                trajectory.latitude,
                trajectory.normalizedLongitude(),
                frameTimeNanos,
            )
        }
    }

    fun stop() {
        enabled = false
        gestureGate.reset()
        trajectory.stop()
        if (callbackPosted) choreographer.removeFrameCallback(frameCallback)
        callbackPosted = false
    }

    private fun onFrame(frameTimeNanos: Long) {
        callbackPosted = false
        if (!gestureGate.allowsCameraMutation(enabled)) return
        if (trajectory.step(frameTimeNanos)) {
            applyCamera(trajectory.latitude, trajectory.normalizedLongitude(), frameTimeNanos)
        }
        if (!trajectory.settled) schedule()
    }

    private fun schedule() {
        if (callbackPosted || !gestureGate.allowsCameraMutation(enabled)) return
        callbackPosted = true
        choreographer.postFrameCallback(frameCallback)
    }
}
