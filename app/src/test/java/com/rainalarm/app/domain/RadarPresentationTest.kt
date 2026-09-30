package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.Locale

class RadarPresentationTest {
    @Test fun `travel follow changes only for deliberate exit reasons`() {
        assertTrue(RadarTravelModePolicy.next(false, RadarTravelTransitionReason.USER_ENTER))
        assertFalse(RadarTravelModePolicy.next(true, RadarTravelTransitionReason.USER_EXIT))
        assertFalse(RadarTravelModePolicy.next(true, RadarTravelTransitionReason.USER_MAP_GESTURE))
        assertFalse(RadarTravelModePolicy.next(true, RadarTravelTransitionReason.PERMISSION_LOST))
        listOf(
            RadarTravelTransitionReason.TIMELINE_MANUAL,
            RadarTravelTransitionReason.PROGRAMMATIC_CAMERA,
            RadarTravelTransitionReason.TRANSIENT_FIX,
            RadarTravelTransitionReason.LIFECYCLE,
        ).forEach { reason -> assertTrue(RadarTravelModePolicy.next(true, reason)) }
    }

    @Test fun `only an owned live map pointer accepts MapLibre gesture camera start`() {
        val ownership = RadarMapGestureOwnership()
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = true))
        ownership.pointerStarted(ownedByMap = false)
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = true))
        ownership.pointerStarted(ownedByMap = true)
        assertTrue(ownership.acceptsCameraStart(isMapGestureReason = true))
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = false))
        ownership.pointerFinished()
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = true))
    }

    @Test fun `top control pointer pass-through is excluded from map gesture ownership`() {
        val density = 3f
        val boundary = RadarMapTouchPolicy.topControlsExclusionDp * density
        assertFalse(RadarMapTouchPolicy.pointerOwnedByMap(boundary - 1f, density))
        assertTrue(RadarMapTouchPolicy.pointerOwnedByMap(boundary, density))
        assertTrue(RadarMapTouchPolicy.pointerOwnedByMap(boundary + 200f, density))
    }

    @Test fun `travel auto clock remains exact between frames across rollover and session refresh`() {
        val zone = ZoneId.of("Europe/London")
        val betweenFrames = Clock.fixed(Instant.parse("2026-09-30T12:07:43Z"), zone)
        val first = RadarTravelClockPolicy.snapshot(betweenFrames, zone, true, Locale.UK)
        assertEquals("13:07", first.localMinuteText)
        val rollover = RadarTravelClockPolicy.snapshot(
            Clock.offset(betweenFrames, Duration.ofSeconds(17)), zone, true, Locale.UK,
        )
        assertEquals("13:08", rollover.localMinuteText)

        val midnight = RadarTravelClockPolicy.snapshot(
            Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), zone), zone, true, Locale.UK,
        )
        val nextDay = RadarTravelClockPolicy.snapshot(
            Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), zone), zone, true, Locale.UK,
        )
        assertEquals("23:59", midnight.localMinuteText)
        assertEquals("00:00", nextDay.localMinuteText)

        val oldSession = RadarTravelTimelinePolicy.frame(
            first.epochSeconds, first.epochSeconds - 1_800L, first.epochSeconds - 300L,
            first.epochSeconds + 1_800L, true,
        )
        val refreshedSession = RadarTravelTimelinePolicy.frame(
            first.epochSeconds, first.epochSeconds - 1_200L, first.epochSeconds - 120L,
            first.epochSeconds + 2_400L, true,
        )
        assertEquals(first.epochSeconds,
            first.epochSeconds - 1_800L + oldSession.dataCursorSeconds.toLong())
        assertEquals(first.epochSeconds,
            first.epochSeconds - 1_200L + refreshedSession.dataCursorSeconds.toLong())
        assertEquals(oldSession.displayStartEpochSeconds, refreshedSession.displayStartEpochSeconds)
    }

    @Test fun `travel auto clock follows device zone rules across daylight saving fold`() {
        val zone = ZoneId.of("Europe/London")
        val before = RadarTravelClockPolicy.snapshot(
            Clock.fixed(Instant.parse("2026-10-25T00:59:59Z"), zone), zone, true, Locale.UK,
        )
        val after = RadarTravelClockPolicy.snapshot(
            Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), zone), zone, true, Locale.UK,
        )
        assertEquals("01:59", before.localMinuteText)
        assertEquals("01:00", after.localMinuteText)
    }

    @Test fun `travel timeline holds marker x while wall clock and labels roll`() {
        val first = RadarTravelTimelinePolicy.frame(2_000L, 1_000L, 1_900L, 2_600L, true)
        val later = RadarTravelTimelinePolicy.frame(2_001L, 1_000L, 1_900L, 2_600L, true)
        assertEquals(first.displayCursorSeconds, later.displayCursorSeconds, 0f)
        assertEquals(first.displayStartEpochSeconds + 1L, later.displayStartEpochSeconds)
        assertEquals(1_000f, first.dataCursorSeconds, 0f)
        assertEquals(1_001f, later.dataCursorSeconds, 0f)
        assertTrue(first.wallClockCovered)
    }

    @Test fun `travel timeline clamps honestly and manual takeover maps rolling window`() {
        val frame = RadarTravelTimelinePolicy.frame(2_000L, 1_000L, 1_900L, 2_600L, true)
        assertTrue(frame.wallClockCovered)
        assertEquals(1_000f, frame.dataCursorSeconds, 0f)
        assertEquals(
            600f,
            RadarTravelTimelinePolicy.dataCursorForManualSelection(
                frame.displayCursorSeconds - 400f,
                frame,
                1_000L,
                2_600L,
            ),
            0f,
        )
        assertFalse(RadarTravelTimelinePolicy.frame(
            3_000L, 1_000L, 1_900L, 2_600L, true,
        ).wallClockCovered)
    }
    @Test
    fun entryOpensAtWallClockOnlyWhenForecastCoversIt() {
        val first = 1_000L
        val latestObservation = 1_600L
        val end = 5_200L
        assertEquals(1_080f, RadarEntryClock.initialCursor(first, latestObservation, end, true, 2_080L), 0f)
        assertEquals(600f, RadarEntryClock.initialCursor(first, latestObservation, end, false, 2_080L), 0f)
        assertEquals(600f, RadarEntryClock.initialCursor(first, latestObservation, end, true, 1_500L), 0f)
        assertEquals(600f, RadarEntryClock.initialCursor(first, latestObservation, end, true, 5_201L), 0f)
        assertEquals(4_200f, RadarEntryClock.initialCursor(first, latestObservation, end, true, end), 0f)
    }
    @Test
    fun playbackSpeedScalesElapsedTimeAndLoopsWithoutStopping() {
        assertEquals(15f, RadarPlaybackClock.advance(0f, 0.05, 3_600f, 1f), 0.001f)
        assertEquals(7.5f, RadarPlaybackClock.advance(0f, 0.05, 3_600f, 0.5f), 0.001f)
        assertEquals(30f, RadarPlaybackClock.advance(0f, 0.05, 3_600f, 2f), 0.001f)
        assertEquals(5f, RadarPlaybackClock.advance(3_590f, 0.05, 3_600f, 1f), 0.001f)
        assertEquals(3f, RadarPlaybackClock.advance(3_600f, 0.01, 3_600f, 1f), 0.001f)
        assertEquals(0f, RadarPlaybackClock.advance(0f, 1.0, 0f, 1f), 0f)
        assertEquals(15f, RadarPlaybackClock.advance(0f, 0.40, 3_600f, 1f), 0.001f)
    }

    @Test
    fun entryZoomGivesTwentyMileWidthAtDifferentLatitudesAndDisplayWidths() {
        for (latitude in listOf(0.0, 53.48, 65.0)) for (width in listOf(360, 720, 1080)) {
            val zoom = RadarEntryZoom.forHorizontalMiles(latitude, width)
            assertEquals(20.0, RadarEntryZoom.horizontalMiles(latitude, width, zoom), 0.001)
        }
        assertTrue(RadarEntryZoom.forHorizontalMiles(53.48, 1080) >
            RadarEntryZoom.forHorizontalMiles(53.48, 360))
    }

    @Test
    fun ticksUseRealQuarterHourBoundariesWithContinuousFractionsAndLocalClock() {
        val start = Instant.parse("2026-09-16T08:07:00Z").epochSecond
        val end = Instant.parse("2026-09-16T10:06:00Z").epochSecond
        val ticks = RadarTimelineTicks.between(start, end, ZoneId.of("Europe/London"))
        assertEquals(listOf("09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00"),
            ticks.map { it.label })
        assertEquals(listOf(8L, 23L, 38L, 53L, 68L, 83L, 98L, 113L),
            ticks.map { (it.epochSeconds - start) / 60 })
        assertEquals(8f / 119f, ticks.first().fraction, 0.0001f)
        assertTrue(ticks.zipWithNext().all { it.first.fraction < it.second.fraction })
    }

    @Test fun `timeline labels use locale clock while provider frame cadence remains irrelevant`() {
        val start = Instant.parse("2026-09-16T11:59:00Z").epochSecond
        val end = start + 47 * 60
        val twelveHour = RadarTimelineTicks.between(
            start, end, ZoneId.of("Europe/London"), use24Hour = false, locale = java.util.Locale.US,
        )
        assertEquals(listOf("1:00 PM", "1:15 PM", "1:30 PM", "1:45 PM"), twelveHour.map { it.label })
        assertTrue(twelveHour.all { it.epochSeconds % RadarTimelineTicks.SPACING_SECONDS == 0L })
    }

    @Test fun `measured label collision thinning never moves a label away from its tick`() {
        val fractions = listOf(0f, .25f, .5f, .75f, 1f)
        val roomy = RadarTimelineLabelLayout.arrange(fractions, List(5) { 34 }, 400, 8f, 6f)
        assertTrue(roomy.all { it.visible })
        assertEquals(listOf(17f, 104f, 200f, 296f, 383f), roomy.map { it.anchorPx })

        val narrow = RadarTimelineLabelLayout.arrange(fractions, List(5) { 58 }, 180, 8f, 6f)
        assertTrue(narrow.count { it.visible } < narrow.size)
        assertEquals(29f, narrow.first().anchorPx, 0f)
        assertEquals(151f, narrow.last().anchorPx, 0f)
        assertTrue(narrow.last().visible)
        assertTrue(narrow.zip(fractions).all { (placement, fraction) ->
            val expectedRaw = 8f + (180f - 16f) * fraction
            placement.anchorPx == expectedRaw.coerceIn(29f, 151f)
        })
    }

    @Test fun `entry focus and radar edge swipes are bounded and deliberate`() {
        assertEquals(9.45, RadarEntryFocusPolicy.startZoom(10.0), 0.0001)
        assertEquals(RadarEntryFocusPolicy.START_MARKER_SCALE,
            RadarEntryFocusPolicy.markerScale(0f), 0f)
        assertEquals(1f, RadarEntryFocusPolicy.markerScale(1f), 0f)
        assertEquals(RadarEntryFocusPolicy.START_TITLE_SCALE,
            RadarEntryFocusPolicy.titleScale(-1f), 0f)
        assertEquals(1f, RadarEntryFocusPolicy.titleScale(2f), 0f)
        assertEquals(0, RadarEntryFocusPolicy.mapDurationMillis(0f))
        assertEquals(500, RadarEntryFocusPolicy.mapDurationMillis(.5f))
        assertEquals(1_000, RadarEntryFocusPolicy.mapDurationMillis(1f))
        assertEquals(2_000, RadarEntryFocusPolicy.mapDurationMillis(2f))
        assertTrue(RadarEntryFocusActivationPolicy.shouldPrepare(
            entryPending = true,
            entryGeneration = 4,
            hasResolvedPlace = true,
            waitingForInitialCurrentFix = false,
        ))
        // Travel/follow is deliberately not an input: a retained live page still animates once
        // for each destination-entry generation, while GPS recompositions reuse that generation.
        assertFalse(RadarEntryFocusActivationPolicy.shouldPrepare(true, 0, true, false))
        assertFalse(RadarEntryFocusActivationPolicy.shouldPrepare(false, 4, true, false))
        assertFalse(RadarEntryFocusActivationPolicy.shouldPrepare(true, 4, false, true))

        assertEquals(-1, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 80f, 5f,
        ))
        assertEquals(1, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.NEXT, -80f, 5f,
        ))
        assertEquals(null, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 19f, 0f,
        ))
        assertEquals(null, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 80f, 75f,
        ))
        assertEquals(null, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 80f, 0f, multiTouch = true,
        ))
        assertEquals(-1, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 24f, 2f, durationMillis = 50L,
        ))
        assertEquals(null, RadarPageSwipePolicy.destinationDelta(
            RadarPageEdge.PREVIOUS, 24f, 2f, durationMillis = 500L,
        ))
        assertTrue(RadarPageSwipePolicy.canClaim(RadarPageEdge.PREVIOUS, 5f, 2f, false))
        assertTrue(RadarPageSwipePolicy.canClaim(RadarPageEdge.NEXT, -5f, 2f, false))
        assertFalse(RadarPageSwipePolicy.canClaim(RadarPageEdge.PREVIOUS, -5f, 0f, false))
        assertFalse(RadarPageSwipePolicy.canClaim(RadarPageEdge.NEXT, -5f, 0f, true))
    }

    @Test fun `entry focus completion preserves a panned viewport unless travel follows a fix`() {
        val panned = RadarCameraTarget(52.14, -1.72, 11.75)
        assertEquals(
            panned,
            RadarEntryFocusCompletionPolicy.finalTarget(
                entryTarget = panned,
                completed = true,
                ownsCamera = true,
            ),
        )

        val liveFix = com.rainalarm.app.data.SavedPlace(
            "Current", 53.01, -2.22, isCurrentLocation = true,
            id = com.rainalarm.app.data.CURRENT_LOCATION_ID,
        )
        assertEquals(
            RadarCameraTarget(liveFix.latitude, liveFix.longitude, panned.zoom),
            RadarEntryFocusCompletionPolicy.finalTarget(
                entryTarget = panned,
                completed = true,
                ownsCamera = true,
                followTarget = liveFix,
            ),
        )
    }

    @Test fun `cancelled or superseded entry completion cannot overwrite a newer camera intent`() {
        val target = RadarCameraTarget(51.7, 0.2, 14.5)
        val owner = RadarCameraIntentOwner()
        val first = owner.claim()
        owner.invalidate() // A user pan, recenter, place switch, or later entry.
        assertFalse(owner.owns(first))
        assertEquals(null, RadarEntryFocusCompletionPolicy.finalTarget(
            entryTarget = target,
            completed = true,
            ownsCamera = owner.owns(first),
        ))

        val current = owner.claim()
        assertTrue(owner.owns(current))
        assertEquals(null, RadarEntryFocusCompletionPolicy.finalTarget(
            entryTarget = target,
            completed = false,
            ownsCamera = owner.owns(current),
        ))
    }

    @Test fun `saved marker focus waits for its target and cancels on a different selection`() {
        val request = RadarMarkerFocusRequest(7, "old-place", "new-place")
        assertEquals(RadarMarkerFocusAction.WAIT,
            RadarMarkerFocusPolicy.resolve(request, "old-place"))
        assertEquals(RadarMarkerFocusAction.ANIMATE,
            RadarMarkerFocusPolicy.resolve(request, "new-place"))
        assertEquals(RadarMarkerFocusAction.CANCEL,
            RadarMarkerFocusPolicy.resolve(request, "another-place"))
        assertEquals(RadarMarkerFocusAction.CANCEL,
            RadarMarkerFocusPolicy.resolve(request, null))
    }

    @Test
    fun integratedRainDropHasRadialTipRightAngleTangentSidesAndCircularBack() {
        val radius = 10f
        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)) {
            val drop = NowVisualGeometry.rainDropOutline(bearing, radius)
            val radial = NowVisualGeometry.compassPoint(bearing, 1f)
            assertEquals(kotlin.math.sqrt(2f) * radius,
                drop.tip.first * radial.first + drop.tip.second * radial.second, 0.001f)
            for (join in listOf(drop.firstJoin, drop.secondJoin)) {
                assertEquals(radius, kotlin.math.hypot(join.first, join.second), 0.001f)
                val sideX = drop.tip.first - join.first
                val sideY = drop.tip.second - join.second
                assertEquals(0f, sideX * join.first + sideY * join.second, 0.002f)
            }
            val firstSideX = drop.firstJoin.first - drop.tip.first
            val firstSideY = drop.firstJoin.second - drop.tip.second
            val secondSideX = drop.secondJoin.first - drop.tip.first
            val secondSideY = drop.secondJoin.second - drop.tip.second
            assertEquals(0f, firstSideX * secondSideX + firstSideY * secondSideY, 0.002f)
            assertEquals(270f, drop.arcSweepDegrees, 0f)
            val arcEnd = Math.toRadians((drop.arcStartDegrees + drop.arcSweepDegrees).toDouble())
            assertEquals(drop.firstJoin.first, (kotlin.math.cos(arcEnd) * radius).toFloat(), 0.001f)
            assertEquals(drop.firstJoin.second, (kotlin.math.sin(arcEnd) * radius).toFloat(), 0.001f)
            val arcStart = NowVisualGeometry.compassPoint(bearing + 45.0, radius)
            assertEquals(arcStart.first, drop.secondJoin.first, 0.001f)
            assertEquals(arcStart.second, drop.secondJoin.second, 0.001f)
            val doubleSize = NowVisualGeometry.rainDropOutline(bearing, radius * 2)
            assertEquals(drop.tip.first * 2f, doubleSize.tip.first, 0.001f)
            assertEquals(drop.tip.second * 2f, doubleSize.tip.second, 0.001f)
        }
        assertTrue(runCatching { NowVisualGeometry.rainDropOutline(0.0, 0f) }.isFailure)
        assertTrue(runCatching { NowVisualGeometry.rainDropOutline(Double.NaN, radius) }.isFailure)
        assertEquals(12f, NowVisualGeometry.markerDistance(20f, 12f, 1f), 0f)
        assertEquals(20f, NowVisualGeometry.markerDistance(20f, 12f, 0f), 0f)
        assertTrue(NowVisualGeometry.shouldAnimateRainMarker(true, 315.0, true))
        assertTrue(!NowVisualGeometry.shouldAnimateRainMarker(false, 315.0, true))
        assertTrue(!NowVisualGeometry.shouldAnimateRainMarker(true, null, true))
        assertTrue(!NowVisualGeometry.shouldAnimateRainMarker(true, 315.0, false))
    }
}
