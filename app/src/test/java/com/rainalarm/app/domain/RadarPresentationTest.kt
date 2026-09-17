package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RadarPresentationTest {
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
    fun ticksUseRealHalfHourBoundariesWithContinuousFractionsAndLocalClock() {
        val start = Instant.parse("2026-09-16T08:07:00Z").epochSecond
        val end = Instant.parse("2026-09-16T10:06:00Z").epochSecond
        val ticks = RadarTimelineTicks.between(start, end, ZoneId.of("Europe/London"))
        assertEquals(listOf("09:30", "10:00", "10:30", "11:00"), ticks.map { it.label })
        assertEquals(listOf(23L, 53L, 83L, 113L), ticks.map { (it.epochSeconds - start) / 60 })
        assertEquals(23f / 119f, ticks.first().fraction, 0.0001f)
        assertTrue(ticks.zipWithNext().all { it.first.fraction < it.second.fraction })
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
