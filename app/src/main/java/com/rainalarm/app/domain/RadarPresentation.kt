package com.rainalarm.app.domain

import com.rainalarm.app.data.SavedPlace
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/** Five radar minutes per real second at 1×, wrapping without reloading frames. */
object RadarPlaybackClock {
    const val BASE_SECONDS_PER_SECOND = 300.0
    // A GL texture miss or resumed surface can delay one frame. Do not turn that one
    // delayed frame into a visible jump across a significant part of the radar interval.
    const val MAX_FRAME_ELAPSED_SECONDS = 0.05

    fun advance(cursorSeconds: Float, elapsedSeconds: Double, endSeconds: Float, speed: Float): Float {
        if (!cursorSeconds.isFinite() || !elapsedSeconds.isFinite() || !endSeconds.isFinite() ||
            endSeconds <= 0f || speed <= 0f || !speed.isFinite()) return 0f
        val elapsed = elapsedSeconds.coerceIn(0.0, MAX_FRAME_ELAPSED_SECONDS) * BASE_SECONDS_PER_SECOND * speed
        return ((cursorSeconds.coerceIn(0f, endSeconds).toDouble() + elapsed) % endSeconds).toFloat()
    }
}

/** Enter paused at the actual clock when a cached forecast covers it, otherwise show latest observation. */
object RadarEntryClock {
    fun initialCursor(
        firstEpochSeconds: Long,
        latestObservationEpochSeconds: Long,
        endEpochSeconds: Long,
        forecastAvailable: Boolean,
        nowEpochSeconds: Long,
    ): Float {
        val latest = (latestObservationEpochSeconds - firstEpochSeconds).coerceAtLeast(0L)
        return if (forecastAvailable && nowEpochSeconds in latestObservationEpochSeconds..endEpochSeconds) {
            (nowEpochSeconds - firstEpochSeconds).toFloat()
        } else latest.toFloat()
    }
}

data class RadarTravelTimelineFrame(
    val dataCursorSeconds: Float,
    val displayStartEpochSeconds: Long,
    val displayEndEpochSeconds: Long,
    val displayCursorSeconds: Float,
    val wallClockCovered: Boolean,
)

/** Pure mapping for Travel's rolling wall-clock timeline and fixed on-screen marker. */
object RadarTravelTimelinePolicy {
    const val MARKER_FRACTION = 0.64f

    fun frame(
        nowEpochSeconds: Long,
        dataStartEpochSeconds: Long,
        latestObservationEpochSeconds: Long,
        dataEndEpochSeconds: Long,
        forecastAvailable: Boolean,
    ): RadarTravelTimelineFrame {
        require(dataEndEpochSeconds >= dataStartEpochSeconds)
        val usableEnd = if (forecastAvailable) dataEndEpochSeconds else latestObservationEpochSeconds
        val covered = nowEpochSeconds in dataStartEpochSeconds..usableEnd
        val selected = nowEpochSeconds.coerceIn(dataStartEpochSeconds, usableEnd)
        val duration = (dataEndEpochSeconds - dataStartEpochSeconds).coerceAtLeast(1L)
        val before = (duration * MARKER_FRACTION).toLong()
        val displayStart = nowEpochSeconds - before
        return RadarTravelTimelineFrame(
            dataCursorSeconds = (selected - dataStartEpochSeconds).toFloat(),
            displayStartEpochSeconds = displayStart,
            displayEndEpochSeconds = displayStart + duration,
            displayCursorSeconds = duration * MARKER_FRACTION,
            wallClockCovered = covered,
        )
    }

    fun dataCursorForManualSelection(
        displayCursorSeconds: Float,
        frame: RadarTravelTimelineFrame,
        dataStartEpochSeconds: Long,
        dataEndEpochSeconds: Long,
    ): Float {
        val epoch = frame.displayStartEpochSeconds + displayCursorSeconds.toLong()
        return (epoch.coerceIn(dataStartEpochSeconds, dataEndEpochSeconds) - dataStartEpochSeconds)
            .toFloat()
    }
}

enum class RadarTravelTransitionReason {
    USER_ENTER,
    USER_EXIT,
    USER_MAP_GESTURE,
    TIMELINE_MANUAL,
    PROGRAMMATIC_CAMERA,
    PERMISSION_LOST,
    TRANSIENT_FIX,
    LIFECYCLE,
}

/** Only deliberate user exit, a genuine map gesture, or permission loss ends location follow. */
object RadarTravelModePolicy {
    fun next(current: Boolean, reason: RadarTravelTransitionReason): Boolean = when (reason) {
        RadarTravelTransitionReason.USER_ENTER -> true
        RadarTravelTransitionReason.USER_EXIT,
        RadarTravelTransitionReason.USER_MAP_GESTURE,
        RadarTravelTransitionReason.PERMISSION_LOST,
        -> false
        RadarTravelTransitionReason.TIMELINE_MANUAL,
        RadarTravelTransitionReason.PROGRAMMATIC_CAMERA,
        RadarTravelTransitionReason.TRANSIENT_FIX,
        RadarTravelTransitionReason.LIFECYCLE,
        -> current
    }
}

/** Guards MapLibre's gesture reason with proof that this MapView owns an active pointer stream. */
class RadarMapGestureOwnership {
    private var pointerActive = false

    fun pointerStarted(ownedByMap: Boolean = true) {
        pointerActive = ownedByMap
    }

    fun pointerFinished() {
        pointerActive = false
    }

    fun acceptsCameraStart(isMapGestureReason: Boolean): Boolean =
        isMapGestureReason && pointerActive
}

/** Compose controls overlay this band; their pass-through pointer must never count as a map pan. */
object RadarMapTouchPolicy {
    const val topControlsExclusionDp = 64f

    fun pointerOwnedByMap(pointerYpx: Float, density: Float): Boolean =
        pointerYpx >= topControlsExclusionDp * density.coerceAtLeast(1f)
}

data class RadarTravelClockSnapshot(
    val epochSeconds: Long,
    val localMinuteText: String,
)

/** Exact wall-clock source for Travel AUTO; provider cadence never rounds the visible time. */
object RadarTravelClockPolicy {
    fun snapshot(
        clock: Clock,
        zoneId: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): RadarTravelClockSnapshot = snapshotAt(
        clock.instant().epochSecond,
        zoneId,
        use24Hour,
        locale,
    )

    fun snapshotAt(
        epochSeconds: Long,
        zoneId: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): RadarTravelClockSnapshot {
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        return RadarTravelClockSnapshot(
            epochSeconds,
            DateTimeFormatter.ofPattern(pattern, locale).format(
                Instant.ofEpochSecond(epochSeconds).atZone(zoneId),
            ),
        )
    }
}

/** Web-Mercator camera zoom giving a geographic span across the available map width. */
object RadarEntryZoom {
    private const val EQUATOR_METRES_PER_PIXEL = 156543.03392804097
    private const val METRES_PER_MILE = 1609.344

    fun forHorizontalMiles(latitude: Double, widthPixels: Int, miles: Double = 20.0): Double {
        require(latitude.isFinite() && latitude in -85.0..85.0)
        require(widthPixels > 0 && miles > 0.0 && miles.isFinite())
        val metresPerPixel = miles * METRES_PER_MILE / widthPixels
        return (ln(EQUATOR_METRES_PER_PIXEL * cos(latitude * PI / 180.0) / metresPerPixel) / ln(2.0))
            .coerceIn(3.0, 21.0)
    }

    fun horizontalMiles(latitude: Double, widthPixels: Int, zoom: Double): Double =
        EQUATOR_METRES_PER_PIXEL * cos(latitude * PI / 180.0) /
            Math.pow(2.0, zoom) * widthPixels / METRES_PER_MILE
}

object RadarEntryFocusPolicy {
    const val DURATION_MILLIS = 1_000
    const val START_ZOOM_DELTA = 0.55
    const val START_MARKER_SCALE = 1.38f
    const val START_TITLE_SCALE = 1.04f

    fun startZoom(finalZoom: Double): Double =
        (finalZoom - START_ZOOM_DELTA).coerceAtLeast(2.0)

    fun mapDurationMillis(platformAnimatorScale: Float): Int =
        if (!platformAnimatorScale.isFinite() || platformAnimatorScale <= 0f) 0
        else (DURATION_MILLIS * platformAnimatorScale.coerceAtMost(10f)).toInt().coerceAtLeast(1)

    fun markerScale(progress: Float): Float =
        START_MARKER_SCALE - (START_MARKER_SCALE - 1f) * progress.coerceIn(0f, 1f)

    fun titleScale(progress: Float): Float =
        START_TITLE_SCALE - (START_TITLE_SCALE - 1f) * progress.coerceIn(0f, 1f)
}

/**
 * A Radar entry is owned by the destination visibility generation, not by the current session or
 * Travel state. Live GPS fixes may recompose the retained page many times without replaying it.
 */
object RadarEntryFocusActivationPolicy {
    fun shouldPrepare(
        entryPending: Boolean,
        entryGeneration: Int,
        hasResolvedPlace: Boolean,
        waitingForInitialCurrentFix: Boolean,
    ): Boolean = entryPending && entryGeneration > 0 && hasResolvedPlace &&
        !waitingForInitialCurrentFix
}

/**
 * Monotonic ownership for camera operations which outlive the Compose event that started them.
 * A delayed entry-animation completion may only write to the camera while it still owns the
 * current token. A pan, recenter, place change, or newer entry invalidates that token first.
 */
class RadarCameraIntentOwner {
    private var generation = 0L

    fun claim(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun owns(token: Long): Boolean = token == generation
}

/** Keeps a normal Radar return anchored to its captured viewport; only Travel follows a new fix. */
object RadarEntryFocusCompletionPolicy {
    fun finalTarget(
        entryTarget: RadarCameraTarget,
        completed: Boolean,
        ownsCamera: Boolean,
        followTarget: SavedPlace? = null,
    ): RadarCameraTarget? {
        if (!completed || !ownsCamera) return null
        return followTarget?.let {
            entryTarget.copy(latitude = it.latitude, longitude = it.longitude)
        } ?: entryTarget
    }
}

data class RadarMarkerFocusRequest(
    val token: Int,
    val previousPlaceId: String?,
    val targetPlaceId: String,
)

enum class RadarMarkerFocusAction { WAIT, ANIMATE, CANCEL }

/**
 * Keeps the marker-only focus transition deterministic while a newly saved place propagates
 * through the persisted place collection. A different selection cancels a pending transition.
 */
object RadarMarkerFocusPolicy {
    fun resolve(request: RadarMarkerFocusRequest, selectedPlaceId: String?): RadarMarkerFocusAction =
        when (selectedPlaceId) {
            request.targetPlaceId -> RadarMarkerFocusAction.ANIMATE
            request.previousPlaceId -> RadarMarkerFocusAction.WAIT
            else -> RadarMarkerFocusAction.CANCEL
        }
}

enum class RadarPageEdge { PREVIOUS, NEXT }

object RadarPageSwipePolicy {
    /** Start tracking quickly once an edge gesture is clearly horizontal and inward. */
    const val CLAIM_DISTANCE_DP = 4f
    const val CLAIM_HORIZONTAL_DOMINANCE = 1.05f
    /** Settle thresholds tuned to feel like the ordinary HorizontalPager drag. */
    const val THRESHOLD_DP = 56f
    const val FAST_SWIPE_MINIMUM_DP = 20f
    const val VELOCITY_THRESHOLD_DP_PER_SECOND = 400f
    const val HORIZONTAL_DOMINANCE = 1.15f

    fun canClaim(edge: RadarPageEdge, deltaXDp: Float, deltaYDp: Float, multiTouch: Boolean): Boolean {
        if (multiTouch || !deltaXDp.isFinite() || !deltaYDp.isFinite()) return false
        val inward = when (edge) {
            RadarPageEdge.PREVIOUS -> deltaXDp > 0f
            RadarPageEdge.NEXT -> deltaXDp < 0f
        }
        return inward && kotlin.math.abs(deltaXDp) >= CLAIM_DISTANCE_DP &&
            kotlin.math.abs(deltaXDp) >= kotlin.math.abs(deltaYDp) * CLAIM_HORIZONTAL_DOMINANCE
    }

    fun destinationDelta(
        edge: RadarPageEdge,
        deltaXDp: Float,
        deltaYDp: Float,
        multiTouch: Boolean = false,
        durationMillis: Long = Long.MAX_VALUE,
    ): Int? {
        if (multiTouch || !deltaXDp.isFinite() || !deltaYDp.isFinite()) return null
        val distance = kotlin.math.abs(deltaXDp)
        val velocity = if (durationMillis in 1 until Long.MAX_VALUE) {
            distance * 1_000f / durationMillis
        } else 0f
        val committed = distance >= THRESHOLD_DP ||
            (distance >= FAST_SWIPE_MINIMUM_DP && velocity >= VELOCITY_THRESHOLD_DP_PER_SECOND)
        if (!committed ||
            kotlin.math.abs(deltaXDp) < kotlin.math.abs(deltaYDp) * HORIZONTAL_DOMINANCE) return null
        return when (edge) {
            RadarPageEdge.PREVIOUS -> (-1).takeIf { deltaXDp > 0f }
            RadarPageEdge.NEXT -> 1.takeIf { deltaXDp < 0f }
        }
    }
}

data class RadarTimeTick(val epochSeconds: Long, val fraction: Float, val label: String)

object RadarTimelineTicks {
    const val SPACING_SECONDS = 15 * 60L

    fun between(
        startEpochSeconds: Long,
        endEpochSeconds: Long,
        zone: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): List<RadarTimeTick> {
        require(endEpochSeconds > startEpochSeconds)
        val first = Math.floorDiv(startEpochSeconds + SPACING_SECONDS - 1, SPACING_SECONDS) * SPACING_SECONDS
        val formatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
        return generateSequence(first) { previous -> previous + SPACING_SECONDS }
            .takeWhile { it <= endEpochSeconds }
            .map { timestamp ->
                RadarTimeTick(
                    timestamp,
                    ((timestamp - startEpochSeconds).toDouble() /
                        (endEpochSeconds - startEpochSeconds)).toFloat().coerceIn(0f, 1f),
                    formatter.format(Instant.ofEpochSecond(timestamp).atZone(zone)),
                )
            }.toList()
    }
}

data class RadarTimelineLabelPlacement(
    val anchorPx: Float,
    val visible: Boolean,
)

/** Measured label placement: labels never leave or drift away from their own tick anchor. */
object RadarTimelineLabelLayout {
    fun arrange(
        fractions: List<Float>,
        labelWidthsPx: List<Int>,
        widthPx: Int,
        innerInsetPx: Float,
        gapPx: Float,
    ): List<RadarTimelineLabelPlacement> {
        require(fractions.size == labelWidthsPx.size)
        if (widthPx <= 0 || fractions.isEmpty()) return emptyList()
        val usable = (widthPx - innerInsetPx * 2f).coerceAtLeast(0f)
        val raw = fractions.map { innerInsetPx + usable * it.coerceIn(0f, 1f) }
        val anchors = raw.mapIndexed { index, value ->
            val half = (labelWidthsPx[index].coerceAtLeast(0) / 2f)
                .coerceAtMost(widthPx / 2f)
            value.coerceIn(half, widthPx - half)
        }
        val visible = BooleanArray(fractions.size)
        var previousRight = Float.NEGATIVE_INFINITY
        anchors.indices.forEach { index ->
            val half = labelWidthsPx[index] / 2f
            val left = anchors[index] - half
            if (left >= previousRight + gapPx) {
                visible[index] = true
                previousRight = anchors[index] + half
            }
        }
        // Prefer a real final quarter-hour label over the preceding colliding label.
        val last = anchors.lastIndex
        if (!visible[last] && last > 0) {
            val lastLeft = anchors[last] - labelWidthsPx[last] / 2f
            val previousVisible = (last - 1 downTo 0).firstOrNull { visible[it] }
            val beforePrevious = previousVisible?.let { prior ->
                (prior - 1 downTo 0).firstOrNull { visible[it] }
            }
            val safeLeft = beforePrevious?.let {
                anchors[it] + labelWidthsPx[it] / 2f + gapPx
            } ?: Float.NEGATIVE_INFINITY
            if (lastLeft >= safeLeft) {
                if (previousVisible != null) visible[previousVisible] = false
                visible[last] = true
            }
        }
        return anchors.mapIndexed { index, anchor ->
            RadarTimelineLabelPlacement(anchor, visible[index])
        }
    }
}
