package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class RadarLoadDeadlineTest {
    @Test fun primaryLeavesFallbackBudgetWithinFiniteTotal() {
        for (mode in RadarLoadMode.entries) {
            val primary = RadarLoadDeadline.primary(mode)
            val total = RadarLoadDeadline.total(mode)
            assertTrue(primary > 0 && total > primary)
            assertTrue(total - primary >= 30_000L)
            assertTrue(RadarLoadDeadline.opera(mode, afterRegional = true) > 0L)
            assertTrue(RadarLoadDeadline.opera(mode, afterRegional = false) >
                RadarLoadDeadline.opera(mode, afterRegional = true))
            assertEquals(total - primary,
                RadarLoadDeadline.remaining(total, 0L, primary * 1_000_000L))
            assertEquals(0L, RadarLoadDeadline.remaining(total, 0L, (total + 1) * 1_000_000L))
        }
        assertTrue(RadarLoadDeadline.NOW_TOTAL_MILLIS > RadarLoadDeadline.ANALYSIS_TOTAL_MILLIS)
        assertEquals(RadarLoadDeadline.SCREEN_TOTAL_MILLIS,
            RadarLoadDeadline.remaining(RadarLoadDeadline.SCREEN_TOTAL_MILLIS, 100L, 0L))
    }

    @Test fun primaryTimeoutSelectsFallbackAndExternalCancellationDoesNot() = runBlocking {
        val timedOut = RadarLoadDeadline.attemptPrimary(20L) {
            delay(1_000L)
            "regional"
        }
        assertEquals(PrimaryRadarAttempt.TimedOut, timedOut)
        val usableFallbackBudget = RadarLoadDeadline.remaining(1_000L, 0L, 20_000_000L)
        assertTrue(usableFallbackBudget > 0L)
        assertEquals(PrimaryRadarAttempt.Ready("regional"),
            RadarLoadDeadline.attemptPrimary(1_000L) { "regional" })
        assertTrue(RadarLoadDeadline.attemptPrimary<String>(1_000L) {
            throw IllegalStateException("provider failed")
        } is PrimaryRadarAttempt.Failed)
        var propagated = false
        try {
            RadarLoadDeadline.attemptPrimary<String>(1_000L) {
                throw CancellationException("screen left")
            }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
