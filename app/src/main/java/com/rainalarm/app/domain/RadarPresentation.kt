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

/** Seamless handoff from Travel's authoritative AUTO instant into manual playback. */
object RadarPlaybackHandoffPolicy {
    fun startingCursorSeconds(
        currentCursorSeconds: Float,
        autoFrame: RadarTravelAutoFrame?,
        endOffsetSeconds: Float,
    ): Float {
        val end = endOffsetSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val candidate = autoFrame?.timeline?.dataCursorSeconds ?: currentCursorSeconds
        return candidate.takeIf { it.isFinite() }?.coerceIn(0f, end) ?: 0f
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
    /** The one wall-clock instant represented by AUTO, whether or not radar covers it. */
    val selectedEpochSeconds: Long,
    val dataCursorSeconds: Float,
    val displayStartEpochSeconds: Long,
    val displayEndEpochSeconds: Long,
    val displayCursorSeconds: Float,
    val wallClockCovered: Boolean,
)

/** Pure mapping from Travel's wall clock to the radar session's invariant timeline. */
object RadarTravelTimelinePolicy {
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
        // AUTO is an overlay on the same domain used by normal playback. Never roll or re-space
        // the base timeline merely because Travel is active.
        val displayCursor = (nowEpochSeconds - dataStartEpochSeconds).coerceIn(0L, duration)
        return RadarTravelTimelineFrame(
            selectedEpochSeconds = nowEpochSeconds,
            dataCursorSeconds = (selected - dataStartEpochSeconds).toFloat(),
            displayStartEpochSeconds = dataStartEpochSeconds,
            displayEndEpochSeconds = dataEndEpochSeconds,
            displayCursorSeconds = displayCursor.toFloat(),
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

/**
 * One immutable AUTO publication. Every time-bearing Radar consumer must read this object instead
 * of independently reconstructing "now" from provider cadence, a slider value, or a formatted
 * clock string.
 */
data class RadarTravelAutoFrame(
    val selectedEpochSeconds: Long,
    val localMinuteText: String,
    val timeline: RadarTravelTimelineFrame,
) {
    init {
        require(selectedEpochSeconds == timeline.selectedEpochSeconds)
    }

    val wallClockCovered: Boolean get() = timeline.wallClockCovered
    val overlayEpochSeconds: Long? get() = selectedEpochSeconds.takeIf { wallClockCovered }
    val satelliteEpochSeconds: Long get() = selectedEpochSeconds
    val displayFraction: Float get() = timeline.displayCursorSeconds /
        (timeline.displayEndEpochSeconds - timeline.displayStartEpochSeconds)
            .coerceAtLeast(1L).toFloat()
}

object RadarTravelAutoPolicy {
    fun frame(
        selectedEpochSeconds: Long,
        dataStartEpochSeconds: Long,
        latestObservationEpochSeconds: Long,
        dataEndEpochSeconds: Long,
        forecastAvailable: Boolean,
        zoneId: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): RadarTravelAutoFrame {
        val timeline = RadarTravelTimelinePolicy.frame(
            selectedEpochSeconds,
            dataStartEpochSeconds,
            latestObservationEpochSeconds,
            dataEndEpochSeconds,
            forecastAvailable,
        )
        val clock = RadarTravelClockPolicy.snapshotAt(
            selectedEpochSeconds,
            zoneId,
            use24Hour,
            locale,
        )
        return RadarTravelAutoFrame(
            selectedEpochSeconds = selectedEpochSeconds,
            localMinuteText = clock.localMinuteText,
            timeline = timeline,
        )
    }

    /** Keeps the rolling AUTO clock visible while no usable radar timeline is installed. */
    fun unavailableFrame(
        selectedEpochSeconds: Long,
        displayDurationSeconds: Long,
        zoneId: ZoneId,
        use24Hour: Boolean = true,
        locale: Locale = Locale.getDefault(),
    ): RadarTravelAutoFrame {
        val duration = displayDurationSeconds.coerceAtLeast(1L)
        return frame(
            selectedEpochSeconds = selectedEpochSeconds,
            dataStartEpochSeconds = selectedEpochSeconds - duration,
            latestObservationEpochSeconds = selectedEpochSeconds - 1L,
            dataEndEpochSeconds = selectedEpochSeconds,
            forecastAvailable = false,
            zoneId = zoneId,
            use24Hour = use24Hour,
            locale = locale,
        )
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
    private var cameraGestureClaimed = false

    /** Returns true when the map must immediately pause automated camera writes. */
    fun pointerStarted(ownedByMap: Boolean = true): Boolean {
        pointerActive = ownedByMap
        cameraGestureClaimed = false
        return pointerActive
    }

    /**
     * Returns true only for an owned tap/cancel that never became a camera gesture. A claimed
     * pan/pinch retains the resulting manual camera until the established Travel action resumes.
     */
    fun pointerFinished(): Boolean {
        val resumeAutomaticCamera = pointerActive && !cameraGestureClaimed
        pointerActive = false
        cameraGestureClaimed = false
        return resumeAutomaticCamera
    }

    fun acceptsCameraStart(isMapGestureReason: Boolean): Boolean {
        val accepted = isMapGestureReason && pointerActive
        if (accepted) cameraGestureClaimed = true
        return accepted
    }
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

data class RadarTimeTick(
    val epochSeconds: Long,
    val fraction: Float,
    val label: String,
    val selected: Boolean = false,
)

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

    /** Adds AUTO's exact instant without modifying or replacing the ordinary tick grid. */
    fun withSelected(
        ticks: List<RadarTimeTick>,
        startEpochSeconds: Long,
        endEpochSeconds: Long,
        selectedEpochSeconds: Long,
        selectedLabel: String,
    ): List<RadarTimeTick> {
        if (endEpochSeconds <= startEpochSeconds) return ticks
        val fraction = ((selectedEpochSeconds - startEpochSeconds).toDouble() /
            (endEpochSeconds - startEpochSeconds)).toFloat().coerceIn(0f, 1f)
        // Do not guess collision from epoch distance. Text widths, density and locale determine
        // whether the added AUTO display can coexist; the measured layout owns that choice. Keep
        // an exact ordinary tick as a separate item so normal/AUTO base geometry remains identical.
        return (ticks + RadarTimeTick(
            selectedEpochSeconds,
            fraction,
            selectedLabel,
            selected = true,
        )).sortedBy(RadarTimeTick::epochSeconds)
    }
}

/** Slider and tick labels use the same measured track and the same thumb-centre inset. */
object RadarTimelineTrackGeometry {
    // Material 3 1.4 uses a 4dp-wide horizontal handle. Slider lays its value domain between the
    // handle centres, so the tick anchors use the same 2dp inset rather than an independently
    // guessed row padding.
    const val THUMB_WIDTH_DP = 4f
    const val INNER_INSET_DP = THUMB_WIDTH_DP / 2f

    fun anchorPx(fraction: Float, widthPx: Int, innerInsetPx: Float): Float {
        if (widthPx <= 0) return 0f
        val safeInset = innerInsetPx.coerceIn(0f, widthPx / 2f)
        return safeInset + (widthPx - safeInset * 2f) * fraction.coerceIn(0f, 1f)
    }


    fun measuredTrackWidthPx(
        containerWidthPx: Int,
        playColumnWidthPx: Int,
        gapWidthPx: Int,
    ): Int = (containerWidthPx - playColumnWidthPx - gapWidthPx).coerceAtLeast(0)
}

data class RadarTimelineInlineAutoLabelPlacement(
    val leftPx: Float,
    val topPx: Float,
    val thumbAnchorPx: Float,
    /** Conservative bounds including side-bearing/antialiasing overhang beyond advance width. */
    val safeVisualLeftPx: Float,
    val safeVisualRightPx: Float,
)

/** Places AUTO wholly inside the filled track and immediately before the current-time thumb. */
object RadarTimelineInlineAutoLabelPolicy {
    fun place(
        fraction: Float,
        widthPx: Int,
        heightPx: Int,
        labelWidthPx: Int,
        labelHeightPx: Int,
        trackInsetPx: Float,
        thumbWidthPx: Float,
        gapPx: Float,
        glyphSafetyInsetPx: Float,
    ): RadarTimelineInlineAutoLabelPlacement? {
        if (widthPx <= 0 || heightPx <= 0 || labelWidthPx <= 0 || labelHeightPx <= 0) return null
        val safeTrackInset = trackInsetPx.coerceIn(0f, widthPx / 2f)
        val thumbAnchor = RadarTimelineTrackGeometry.anchorPx(
            fraction = fraction,
            widthPx = widthPx,
            innerInsetPx = safeTrackInset,
        )
        val safety = glyphSafetyInsetPx.coerceAtLeast(0f)
        val safeVisualRight = thumbAnchor - thumbWidthPx.coerceAtLeast(0f) / 2f -
            gapPx.coerceAtLeast(0f)
        // Advance width is not a guarantee about the final antialiased pixel. Keep the measured
        // Text box an additional safety inset left of the visual boundary and render overflow.
        val labelRight = safeVisualRight - safety
        val labelLeft = labelRight - labelWidthPx
        val safeVisualLeft = labelLeft - safety
        // Omit instead of escaping into the Play/gap area or the unfilled side.
        if (safeVisualLeft < safeTrackInset || safeVisualRight > thumbAnchor) return null
        return RadarTimelineInlineAutoLabelPlacement(
            leftPx = labelLeft,
            topPx = ((heightPx - labelHeightPx) / 2f).coerceAtLeast(0f),
            thumbAnchorPx = thumbAnchor,
            safeVisualLeftPx = safeVisualLeft,
            safeVisualRightPx = safeVisualRight,
        )
    }
}

data class RadarTimelineLabelPlacement(
    /** Exact Slider-domain anchor used by the marker line. */
    val anchorPx: Float,
    /** Label centre, clamped only to keep the text inside the measured container. */
    val labelCenterPx: Float,
    val labelVisible: Boolean,
    val markerVisible: Boolean,
)

/** Measured label placement: labels never leave or drift away from their own tick anchor. */
object RadarTimelineLabelLayout {
    fun arrange(
        fractions: List<Float>,
        labelWidthsPx: List<Int>,
        widthPx: Int,
        innerInsetPx: Float,
        gapPx: Float,
        selected: List<Boolean> = List(fractions.size) { false },
    ): List<RadarTimelineLabelPlacement> {
        require(fractions.size == labelWidthsPx.size && selected.size == fractions.size)
        if (widthPx <= 0 || fractions.isEmpty()) return emptyList()
        val anchors = fractions.map {
            RadarTimelineTrackGeometry.anchorPx(it, widthPx, innerInsetPx)
        }
        val labelCenters = anchors.mapIndexed { index, value ->
            val half = (labelWidthsPx[index].coerceAtLeast(0) / 2f)
                .coerceAtMost(widthPx / 2f)
            value.coerceIn(half, widthPx - half)
        }
        val visible = BooleanArray(fractions.size)
        val intervals = anchors.indices.map { index ->
            val half = labelWidthsPx[index].coerceAtLeast(0) / 2f
            (labelCenters[index] - half) to (labelCenters[index] + half)
        }
        val selectedIndex = selected.indexOfFirst { it }
        val reserved = mutableListOf<Pair<Float, Float>>()
        if (selectedIndex >= 0) {
            visible[selectedIndex] = true
            reserved += intervals[selectedIndex]
        }
        fun collides(candidate: Pair<Float, Float>): Boolean = reserved.any { occupied ->
            candidate.first < occupied.second + gapPx &&
                candidate.second > occupied.first - gapPx
        }
        anchors.indices.forEach { index ->
            if (index == selectedIndex) return@forEach
            val markerClashesWithSelected = selectedIndex >= 0 &&
                anchors[index] >= intervals[selectedIndex].first - gapPx &&
                anchors[index] <= intervals[selectedIndex].second + gapPx
            if (markerClashesWithSelected || collides(intervals[index])) return@forEach
            visible[index] = true
            reserved += intervals[index]
        }
        // Preserve manual-mode behaviour: prefer the final real tick over the preceding label
        // when space is tight. AUTO never displaces its already-reserved selected label.
        val last = anchors.lastIndex
        if (selectedIndex < 0 && !visible[last] && last > 0) {
            val previousVisible = (last - 1 downTo 0).firstOrNull { visible[it] }
            val withoutPrevious = reserved.toMutableList().apply {
                previousVisible?.let { remove(intervals[it]) }
            }
            val lastFits = withoutPrevious.none { occupied ->
                intervals[last].first < occupied.second + gapPx &&
                    intervals[last].second > occupied.first - gapPx
            }
            if (lastFits) {
                if (previousVisible != null) visible[previousVisible] = false
                visible[last] = true
            }
        }
        return anchors.mapIndexed { index, anchor ->
            RadarTimelineLabelPlacement(
                anchorPx = anchor,
                labelCenterPx = labelCenters[index],
                labelVisible = visible[index],
                markerVisible = visible[index],
            )
        }
    }
}
