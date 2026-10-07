package com.rainalarm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarForegroundResumeRefreshPolicyTest {
    private val radar = RadarForegroundStreamFreshness(
        acquiredAtElapsedMillis = 1_000L,
        cadenceSeconds = 300L,
    )

    private fun started(): RadarForegroundResumeRefreshState =
        RadarForegroundResumeRefreshPolicy.observeForeground(
            RadarForegroundResumeRefreshState(),
            appForeground = true,
            nowElapsedMillis = 100L,
        )

    private fun resumed(
        backgroundedAt: Long,
        resumedAt: Long,
    ): RadarForegroundResumeRefreshState {
        val background = RadarForegroundResumeRefreshPolicy.observeForeground(
            started(),
            appForeground = false,
            nowElapsedMillis = backgroundedAt,
        )
        return RadarForegroundResumeRefreshPolicy.observeForeground(
            background,
            appForeground = true,
            nowElapsedMillis = resumedAt,
        )
    }

    @Test fun `startup and task switches without a crossed publication opportunity reuse data`() {
        val startup = started()
        assertTrue(startup.hasObservedForeground)
        assertNull(startup.pendingTransition)

        val state = resumed(backgroundedAt = 120_000L, resumedAt = 180_000L)
        val decision = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            state, screenActive = true, nowElapsedMillis = 180_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertTrue(decision.dueStreams.isEmpty())
        assertNull(decision.state.pendingTransition)

        val alreadyMissed = resumed(backgroundedAt = 400_000L, resumedAt = 401_000L)
        val trivialSwitch = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            alreadyMissed, screenActive = true, nowElapsedMillis = 401_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertTrue(trivialSwitch.dueStreams.isEmpty())

        val laterWindow = resumed(backgroundedAt = 500_000L, resumedAt = 700_000L)
        val laterDecision = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            laterWindow, screenActive = true, nowElapsedMillis = 700_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), laterDecision.dueStreams)
    }

    @Test fun `crossing provider cadence and arrival grace refreshes exactly once`() {
        val dueAt = RadarForegroundResumeRefreshPolicy.nextOpportunityElapsedMillis(1_000L, 300L)
        assertEquals(346_000L, dueAt)
        val state = resumed(backgroundedAt = 200_000L, resumedAt = dueAt)
        val first = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            state, screenActive = true, nowElapsedMillis = dueAt,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), first.dueStreams)
        val repeated = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            first.state, screenActive = true, nowElapsedMillis = dueAt + 60_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertTrue(repeated.dueStreams.isEmpty())
        assertNull(repeated.transition)
    }

    @Test fun `resume on another page defers the single evaluation until Radar entry`() {
        val state = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val deferred = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            state, screenActive = false, nowElapsedMillis = 400_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertTrue(deferred.dueStreams.isEmpty())
        assertEquals(state.pendingTransition, deferred.state.pendingTransition)

        val entered = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            deferred.state, screenActive = true, nowElapsedMillis = 410_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), entered.dueStreams)
        assertEquals(400_000L, entered.transition?.resumedAtElapsedMillis)
    }

    @Test fun `background state never consumes or starts a refresh`() {
        val background = RadarForegroundResumeRefreshPolicy.observeForeground(
            started(), appForeground = false, nowElapsedMillis = 200_000L,
        ).copy(
            pendingTransition = RadarForegroundTransition(1L, 200_000L, 500_000L),
        )
        val decision = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            background, screenActive = true, nowElapsedMillis = 500_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radar),
        )
        assertTrue(decision.dueStreams.isEmpty())
        assertFalse(decision.state.appForeground)
        assertTrue(decision.state.pendingTransition != null)
    }

    @Test fun `enabled streams use independent cadence and omitted layers cannot refresh`() {
        val state = resumed(backgroundedAt = 100_000L, resumedAt = 700_000L)
        val decision = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            state, screenActive = true, nowElapsedMillis = 700_000L,
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to radar,
                RadarForegroundRefreshStream.CLOUDS to RadarForegroundStreamFreshness(
                    acquiredAtElapsedMillis = 500_000L,
                    cadenceSeconds = 600L,
                ),
                RadarForegroundRefreshStream.LIGHTNING to RadarForegroundStreamFreshness(
                    acquiredAtElapsedMillis = 1_000L,
                    cadenceSeconds = 300L,
                ),
            ),
        )
        assertEquals(
            setOf(RadarForegroundRefreshStream.RADAR, RadarForegroundRefreshStream.LIGHTNING),
            decision.dueStreams,
        )
        assertFalse(RadarForegroundRefreshStream.CLOUDS in decision.dueStreams)
        assertFalse(RadarForegroundRefreshStream.WIND in decision.dueStreams)
    }

    @Test fun `last attempt prevents a failure loop and successful acquisition freshens the clock`() {
        val failedAttempt = RadarForegroundStreamFreshness(
            acquiredAtElapsedMillis = 1_000L,
            lastAttemptAtElapsedMillis = 400_000L,
            cadenceSeconds = 300L,
        )
        val state = resumed(backgroundedAt = 405_000L, resumedAt = 500_000L)
        val afterFailure = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
            state, true, 500_000L,
            mapOf(RadarForegroundRefreshStream.RADAR to failedAttempt),
        )
        assertTrue(afterFailure.dueStreams.isEmpty())

        val successfulReplacement = failedAttempt.copy(
            acquiredAtElapsedMillis = 800_000L,
            lastAttemptAtElapsedMillis = 800_000L,
        )
        assertEquals(
            1_145_000L,
            RadarForegroundResumeRefreshPolicy.nextOpportunityElapsedMillis(
                requireNotNull(successfulReplacement.freshnessAnchorElapsedMillis),
                successfulReplacement.cadenceSeconds,
            ),
        )
    }
}
