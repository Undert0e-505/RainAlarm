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
    @Test fun `AUTO play handoff replaces a stale cursor with the displayed Travel instant`() {
        val selected = Instant.parse("2026-10-01T05:30:42Z").epochSecond
        val start = selected - 183L
        val auto = RadarTravelAutoPolicy.frame(
            selectedEpochSeconds = selected,
            dataStartEpochSeconds = start,
            latestObservationEpochSeconds = start,
            dataEndEpochSeconds = start + 600L,
            forecastAvailable = true,
            zoneId = ZoneId.of("UTC"),
        )
        val playbackCursor = RadarPlaybackHandoffPolicy.startingCursorSeconds(
            currentCursorSeconds = 25f,
            autoFrame = auto,
            endOffsetSeconds = 600f,
        )
        assertEquals(183f, playbackCursor, 0f)
        assertEquals(selected, start + playbackCursor.toLong())
        assertEquals("05:30", auto.localMinuteText)

        val bracket = RadarTimeline.bracket(
            frameTimes = listOf(start, start + 300L, start + 600L),
            forecastFlags = listOf(false, true, true),
            cursorEpochSeconds = start + playbackCursor.toDouble(),
        )
        assertEquals(0, bracket.firstIndex)
        assertEquals(1, bracket.secondIndex)
        assertEquals(183.0 / 300.0, bracket.fraction, 0.000_001)
    }

    @Test fun `manual play handoff preserves the existing cursor without an AUTO frame`() {
        assertEquals(
            427.5f,
            RadarPlaybackHandoffPolicy.startingCursorSeconds(
                currentCursorSeconds = 427.5f,
                autoFrame = null,
                endOffsetSeconds = 900f,
            ),
            0f,
        )
    }

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
        assertFalse(ownership.pointerStarted(ownedByMap = false))
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = true))
        assertFalse(ownership.pointerFinished())
        assertTrue(ownership.pointerStarted(ownedByMap = true))
        assertTrue(ownership.acceptsCameraStart(isMapGestureReason = true))
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = false))
        assertFalse(ownership.pointerFinished())
        assertFalse(ownership.acceptsCameraStart(isMapGestureReason = true))
    }

    @Test fun `unclaimed map touch resumes follow but pan and pinch retain manual camera`() {
        val ownership = RadarMapGestureOwnership()

        assertTrue(ownership.pointerStarted(ownedByMap = true))
        assertTrue(ownership.pointerFinished())

        assertTrue(ownership.pointerStarted(ownedByMap = true))
        assertTrue(ownership.acceptsCameraStart(isMapGestureReason = true))
        assertFalse(ownership.pointerFinished())

        assertTrue(ownership.pointerStarted(ownedByMap = true))
        assertTrue(ownership.acceptsCameraStart(isMapGestureReason = true))
        // A scale recogniser may report the same owned gesture more than once.
        assertTrue(ownership.acceptsCameraStart(isMapGestureReason = true))
        assertFalse(ownership.pointerFinished())
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
        assertEquals(first.epochSeconds - 1_800L, oldSession.displayStartEpochSeconds)
        assertEquals(first.epochSeconds - 1_200L, refreshedSession.displayStartEpochSeconds)
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

    @Test fun `travel timeline keeps the data domain invariant while current time advances`() {
        val first = RadarTravelTimelinePolicy.frame(2_000L, 1_000L, 1_900L, 2_600L, true)
        val later = RadarTravelTimelinePolicy.frame(2_001L, 1_000L, 1_900L, 2_600L, true)
        assertEquals(first.displayCursorSeconds + 1f, later.displayCursorSeconds, 0f)
        assertEquals(first.displayStartEpochSeconds, later.displayStartEpochSeconds)
        assertEquals(first.displayEndEpochSeconds, later.displayEndEpochSeconds)
        assertEquals(1_000L, first.displayStartEpochSeconds)
        assertEquals(2_600L, first.displayEndEpochSeconds)
        assertEquals(1_000f, first.dataCursorSeconds, 0f)
        assertEquals(1_001f, later.dataCursorSeconds, 0f)
        assertTrue(first.wallClockCovered)
    }

    @Test fun `travel timeline clamps honestly and manual takeover maps the base domain`() {
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

    @Test fun `one AUTO frame owns label slider overlay and satellite instant`() {
        val selected = Instant.parse("2026-10-01T05:30:00Z").epochSecond
        val auto = RadarTravelAutoPolicy.frame(
            selectedEpochSeconds = selected,
            dataStartEpochSeconds = selected - 3_600,
            latestObservationEpochSeconds = selected - 300,
            dataEndEpochSeconds = selected + 3_600,
            forecastAvailable = true,
            zoneId = ZoneId.of("Europe/London"),
            use24Hour = true,
            locale = Locale.UK,
        )
        assertEquals(selected, auto.selectedEpochSeconds)
        assertEquals(selected, auto.overlayEpochSeconds)
        assertEquals(selected, auto.satelliteEpochSeconds)
        assertEquals("06:30", auto.localMinuteText)
        assertEquals(
            selected,
            auto.timeline.displayStartEpochSeconds + auto.timeline.displayCursorSeconds.toLong(),
        )
        assertEquals(
            selected,
            selected - 3_600 + auto.timeline.dataCursorSeconds.toLong(),
        )

        val ticks = RadarTimelineTicks.withSelected(
            RadarTimelineTicks.between(
                auto.timeline.displayStartEpochSeconds,
                auto.timeline.displayEndEpochSeconds,
                ZoneId.of("Europe/London"),
            ),
            auto.timeline.displayStartEpochSeconds,
            auto.timeline.displayEndEpochSeconds,
            selected,
            auto.localMinuteText,
        )
        val selectedTick = ticks.single { it.selected }
        assertEquals(auto.displayFraction, selectedTick.fraction, 0f)
        assertEquals(
            RadarTimelineTrackGeometry.anchorPx(
                auto.displayFraction, 317, RadarTimelineTrackGeometry.INNER_INSET_DP,
            ),
            RadarTimelineTrackGeometry.anchorPx(
                selectedTick.fraction, 317, RadarTimelineTrackGeometry.INNER_INSET_DP,
            ),
            0f,
        )
    }

    @Test fun `AUTO frame stays absolute through session replacement and uncovered time is truthful`() {
        val selected = 10_000L
        val old = RadarTravelAutoPolicy.frame(
            selected, 8_000, 9_600, 12_000, true, ZoneId.of("UTC"),
        )
        val replacement = RadarTravelAutoPolicy.frame(
            selected, 9_000, 9_900, 13_000, true, ZoneId.of("UTC"),
        )
        assertEquals(selected, old.overlayEpochSeconds)
        assertEquals(selected, replacement.overlayEpochSeconds)
        assertEquals(old.selectedEpochSeconds, replacement.selectedEpochSeconds)

        val unavailable = RadarTravelAutoPolicy.frame(
            selected, 8_000, 9_500, 9_900, true, ZoneId.of("UTC"),
        )
        assertFalse(unavailable.wallClockCovered)
        assertEquals(null, unavailable.overlayEpochSeconds)
        assertEquals(selected, unavailable.satelliteEpochSeconds)
        assertEquals(selected, unavailable.timeline.selectedEpochSeconds)

        val loading = RadarTravelAutoPolicy.unavailableFrame(
            selectedEpochSeconds = selected,
            displayDurationSeconds = 3_600,
            zoneId = ZoneId.of("UTC"),
        )
        assertFalse(loading.wallClockCovered)
        assertEquals(null, loading.overlayEpochSeconds)
        assertEquals(selected, loading.selectedEpochSeconds)
        assertEquals(
            selected,
            loading.timeline.displayStartEpochSeconds +
                loading.timeline.displayCursorSeconds.toLong(),
        )
    }

    @Test fun `shared measured track anchors ignore control-cell content and font widths`() {
        val fraction = 0.64f
        val trackWidth = 284
        val thumbInset = RadarTimelineTrackGeometry.INNER_INSET_DP
        val sliderAnchor = RadarTimelineTrackGeometry.anchorPx(fraction, trackWidth, thumbInset)
        for (unrelatedControlContentWidth in listOf(0, 32, 57, 104)) {
            for (fontScale in listOf(0.85f, 1f, 1.5f, 2f)) {
                // The sibling dimensions determine the measured track width before this policy;
                // once measured, both thumb and ticks consume this exact same coordinate space.
                assertTrue(unrelatedControlContentWidth >= 0 && fontScale > 0f)
                assertEquals(
                    sliderAnchor,
                    RadarTimelineTrackGeometry.anchorPx(fraction, trackWidth, thumbInset),
                    0f,
                )
            }
        }
        for (controlContentWidth in listOf(0, 36, 48, 96)) {
            assertTrue(controlContentWidth >= 0)
            assertEquals(
                306,
                RadarTimelineTrackGeometry.measuredTrackWidthPx(
                    containerWidthPx = 360,
                    playColumnWidthPx = 48,
                    gapWidthPx = 6,
                ),
            )
        }
    }

    @Test fun `inline AUTO follows the thumb and remains wholly inside the filled track`() {
        val first = requireNotNull(RadarTimelineInlineAutoLabelPolicy.place(
            fraction = .55f,
            widthPx = 320,
            heightPx = 48,
            labelWidthPx = 42,
            labelHeightPx = 20,
            trackInsetPx = 2f,
            thumbWidthPx = 4f,
            gapPx = 8f,
            glyphSafetyInsetPx = 2f,
        ))
        val later = requireNotNull(RadarTimelineInlineAutoLabelPolicy.place(
            fraction = .80f,
            widthPx = 320,
            heightPx = 48,
            labelWidthPx = 42,
            labelHeightPx = 20,
            trackInsetPx = 2f,
            thumbWidthPx = 4f,
            gapPx = 8f,
            glyphSafetyInsetPx = 2f,
        ))
        assertTrue(later.leftPx > first.leftPx)
        listOf(first, later).forEach { placement ->
            assertEquals(placement.thumbAnchorPx - 12f, placement.leftPx + 42f, 0f)
            assertEquals(placement.thumbAnchorPx - 10f, placement.safeVisualRightPx, 0f)
            assertEquals(placement.leftPx - 2f, placement.safeVisualLeftPx, 0f)
            assertTrue(placement.safeVisualLeftPx >= 2f)
            assertEquals(8f,
                (placement.thumbAnchorPx - 2f) - placement.safeVisualRightPx, 0f)
            assertEquals(14f, placement.topPx, 0f)
        }
    }

    @Test fun `inline AUTO is omitted when the cyan fill cannot contain it`() {
        assertEquals(null, RadarTimelineInlineAutoLabelPolicy.place(
            fraction = .12f,
            widthPx = 320,
            heightPx = 48,
            labelWidthPx = 42,
            labelHeightPx = 20,
            trackInsetPx = 2f,
            thumbWidthPx = 4f,
            gapPx = 8f,
            glyphSafetyInsetPx = 2f,
        ))
        val justFits = RadarTimelineInlineAutoLabelPolicy.place(
            fraction = .20f,
            widthPx = 320,
            heightPx = 48,
            labelWidthPx = 42,
            labelHeightPx = 20,
            trackInsetPx = 2f,
            thumbWidthPx = 4f,
            gapPx = 8f,
            glyphSafetyInsetPx = 2f,
        )
        assertTrue(justFits != null)
        val nearRight = requireNotNull(RadarTimelineInlineAutoLabelPolicy.place(
            fraction = .98f,
            widthPx = 320,
            heightPx = 48,
            labelWidthPx = 42,
            labelHeightPx = 20,
            trackInsetPx = 2f,
            thumbWidthPx = 4f,
            gapPx = 8f,
            glyphSafetyInsetPx = 2f,
        ))
        assertEquals(8f,
            (nearRight.thumbAnchorPx - 2f) - nearRight.safeVisualRightPx, 0f)
        assertTrue(nearRight.safeVisualRightPx < 320f)
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
        assertTrue(roomy.all { it.labelVisible && it.markerVisible })
        assertEquals(listOf(8f, 104f, 200f, 296f, 392f), roomy.map { it.anchorPx })
        assertEquals(listOf(17f, 104f, 200f, 296f, 383f), roomy.map { it.labelCenterPx })

        val narrow = RadarTimelineLabelLayout.arrange(fractions, List(5) { 58 }, 180, 8f, 6f)
        assertTrue(narrow.count { it.labelVisible } < narrow.size)
        assertEquals(8f, narrow.first().anchorPx, 0f)
        assertEquals(172f, narrow.last().anchorPx, 0f)
        assertEquals(29f, narrow.first().labelCenterPx, 0f)
        assertEquals(151f, narrow.last().labelCenterPx, 0f)
        assertTrue(narrow.last().labelVisible)
        assertTrue(narrow.zip(fractions).all { (placement, fraction) ->
            val expectedRaw = 8f + (180f - 16f) * fraction
            placement.anchorPx == expectedRaw
        })
    }

    @Test fun `AUTO overlays the unchanged ordinary tick grid and surviving anchors stay exact`() {
        val start = 10_000L
        val end = start + 3_600L
        val base = RadarTimelineTicks.between(start, end, ZoneId.of("UTC"))
        val auto = RadarTimelineTicks.withSelected(
            ticks = base,
            startEpochSeconds = start,
            endEpochSeconds = end,
            selectedEpochSeconds = start + 1_234L,
            selectedLabel = "00:20",
        )
        assertEquals(base, auto.filterNot { it.selected })
        val exact = RadarTimelineTicks.withSelected(
            ticks = base,
            startEpochSeconds = start,
            endEpochSeconds = end,
            selectedEpochSeconds = base[2].epochSeconds,
            selectedLabel = base[2].label,
        )
        assertEquals(base, exact.filterNot { it.selected })
        assertEquals(2, exact.count { it.epochSeconds == base[2].epochSeconds })

        val baseLayout = RadarTimelineLabelLayout.arrange(
            fractions = base.map { it.fraction },
            labelWidthsPx = List(base.size) { 40 },
            widthPx = 360,
            innerInsetPx = 6f,
            gapPx = 5f,
        )
        val autoLayout = RadarTimelineLabelLayout.arrange(
            fractions = auto.map { it.fraction },
            labelWidthsPx = auto.map { if (it.selected) 48 else 40 },
            selected = auto.map { it.selected },
            widthPx = 360,
            innerInsetPx = 6f,
            gapPx = 5f,
        )
        val autoOrdinary = auto.indices.filter { !auto[it].selected }
        assertEquals(baseLayout.map { it.anchorPx }, autoOrdinary.map { autoLayout[it].anchorPx })
        autoOrdinary.filter { autoLayout[it].markerVisible }.forEachIndexed { _, autoIndex ->
            val epoch = auto[autoIndex].epochSeconds
            val baseIndex = base.indexOfFirst { it.epochSeconds == epoch }
            assertEquals(baseLayout[baseIndex].anchorPx, autoLayout[autoIndex].anchorPx, 0f)
        }
    }

    @Test fun `exact and near AUTO collisions suppress ordinary labels and marker lines`() {
        val placements = RadarTimelineLabelLayout.arrange(
            fractions = listOf(.5f, .5f, .53f, .82f),
            labelWidthsPx = listOf(40, 48, 40, 40),
            selected = listOf(false, true, false, false),
            widthPx = 320,
            innerInsetPx = 6f,
            gapPx = 5f,
        )
        assertTrue(placements[1].labelVisible && placements[1].markerVisible)
        listOf(0, 2).forEach { index ->
            assertFalse(placements[index].labelVisible)
            assertFalse(placements[index].markerVisible)
        }
        assertTrue(placements[3].labelVisible && placements[3].markerVisible)
        assertEquals(placements[0].anchorPx, placements[1].anchorPx, 0f)
    }

    @Test fun `AUTO edge markers retain exact domain anchors while labels remain visible`() {
        for (selectedFraction in listOf(0f, 1f)) {
            val nearby = if (selectedFraction == 0f) .02f else .98f
            val far = if (selectedFraction == 0f) .55f else .45f
            val placements = RadarTimelineLabelLayout.arrange(
                fractions = listOf(nearby, far, selectedFraction),
                labelWidthsPx = listOf(34, 34, 52),
                selected = listOf(false, false, true),
                widthPx = 300,
                innerInsetPx = 7f,
                gapPx = 5f,
            )
            val selected = placements[2]
            assertEquals(if (selectedFraction == 0f) 7f else 293f, selected.anchorPx, 0f)
            assertTrue(selected.labelVisible && selected.markerVisible)
            assertFalse(placements[0].labelVisible)
            assertFalse(placements[0].markerVisible)
            assertTrue(placements[1].labelVisible && placements[1].markerVisible)
        }
        val beforeDomain = RadarTimelineTicks.withSelected(
            ticks = emptyList(),
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
            selectedEpochSeconds = 99L,
            selectedLabel = "00:01",
        ).single()
        val afterDomain = RadarTimelineTicks.withSelected(
            ticks = emptyList(),
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
            selectedEpochSeconds = 201L,
            selectedLabel = "00:03",
        ).single()
        assertEquals(0f, beforeDomain.fraction, 0f)
        assertEquals(1f, afterDomain.fraction, 0f)
        assertTrue(beforeDomain.selected && afterDomain.selected)
    }

    @Test fun `AUTO selected label and marker win measured collisions at every font scale`() {
        val fractions = listOf(.48f, .50f, .78f)
        for (widths in listOf(
            listOf(38, 42, 38),
            listOf(57, 63, 57),
            listOf(76, 84, 76),
        )) {
            val placements = RadarTimelineLabelLayout.arrange(
                fractions = fractions,
                labelWidthsPx = widths,
                selected = listOf(false, true, false),
                widthPx = 320,
                innerInsetPx = RadarTimelineTrackGeometry.INNER_INSET_DP,
                gapPx = 5f,
            )
            assertTrue(placements[1].labelVisible)
            assertTrue(placements[1].markerVisible)
            assertFalse(placements[0].labelVisible)
            assertFalse(placements[0].markerVisible)
            assertTrue(placements[2].labelVisible)
            assertTrue(placements[2].markerVisible)
        }
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
