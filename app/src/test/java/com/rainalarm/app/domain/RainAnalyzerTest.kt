package com.rainalarm.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RainAnalyzerTest {
    private val now = Instant.parse("2026-09-14T10:00:00Z")

    private fun slots(vararg amounts: Double): List<PrecipitationSlot> =
        amounts.mapIndexed { index, amount ->
            PrecipitationSlot(now.plusSeconds(index * 900L), amount, 10 + index * 8)
        }

    @Test
    fun rainingNow_reportsContiguousEndAndPeak() {
        val result = RainAnalyzer.analyze(slots(0.2, 0.8, 0.3, 0.0), now, now)
        assertEquals(RainStatus.RAINING, result.status)
        assertEquals(45, result.endMinutes)
        assertEquals(3.2, result.peakRateMmPerHour!!, 0.001)
    }

    @Test
    fun futureArrival_usesFifteenMinuteBuckets() {
        val result = RainAnalyzer.analyze(slots(0.0, 0.0, 0.2, 0.0), now, now)
        assertEquals(RainStatus.APPROACHING, result.status)
        assertEquals(30, result.arrivalMinutes)
        assertEquals(45, result.endMinutes)
    }

    @Test
    fun contiguousEnd_stopsAtFirstDrySlot() {
        val result = RainAnalyzer.analyze(slots(0.3, 0.4, 0.0, 0.5), now, now)
        assertEquals(30, result.endMinutes)
    }

    @Test
    fun intermittentShowers_firstSpellEndsBeforeSecond() {
        val result = RainAnalyzer.analyze(slots(0.0, 0.2, 0.0, 0.4), now, now)
        assertEquals(15, result.arrivalMinutes)
        assertEquals(30, result.endMinutes)
    }

    @Test
    fun allDry_reportsTwoHourDryState() {
        val result = RainAnalyzer.analyze(slots(0.0, 0.0, 0.0), now, now)
        assertEquals(RainStatus.DRY, result.status)
        assertNull(result.arrivalMinutes)
    }

    @Test
    fun traceValuesBelowThreshold_areDry() {
        val result = RainAnalyzer.analyze(slots(0.099, 0.01, 0.0), now, now)
        assertEquals(RainStatus.DRY, result.status)
    }

    @Test
    fun missingArray_isUnavailable() {
        assertEquals(RainStatus.UNAVAILABLE, RainAnalyzer.analyze(null, now, now).status)
        assertEquals(RainStatus.UNAVAILABLE, RainAnalyzer.analyze(emptyList(), now, now).status)
    }

    @Test
    fun oldSuccessfulData_isExplicitlyStale() {
        val result = RainAnalyzer.analyze(slots(0.2), now, now.minusSeconds(46 * 60L))
        assertEquals(RainStatus.STALE, result.status)
    }

    @Test
    fun timezoneBoundary_usesAbsoluteInstants() {
        val localMidnight = Instant.parse("2026-09-14T23:45:00Z")
        val timeline = listOf(
            PrecipitationSlot(Instant.parse("2026-09-15T00:00:00+00:00"), 0.0),
            PrecipitationSlot(Instant.parse("2026-09-15T01:15:00+01:00"), 0.2),
        )
        val result = RainAnalyzer.analyze(timeline, localMidnight, localMidnight)
        assertEquals(RainStatus.APPROACHING, result.status)
        assertEquals(15, result.arrivalMinutes)
    }

    @Test
    fun nonFifteenMinuteData_isNotPresentedAsPrecise() {
        val result = RainAnalyzer.analyze(slots(0.2), now, now, intervalMinutes = 60)
        assertEquals(RainStatus.UNSUPPORTED_RESOLUTION, result.status)
    }
}
