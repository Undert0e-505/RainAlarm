package com.rainalarm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarForegroundResumeRefreshPolicyTest {
    private val radarFreshness = RadarForegroundStreamFreshness(
        acquiredAtElapsedMillis = 1_000L,
        cadenceSeconds = 300L,
    )
    private val radarState = evaluable(radarFreshness)

    private fun started(): RadarForegroundLifecycleState =
        RadarForegroundResumeRefreshPolicy.observeLifecycle(
            RadarForegroundLifecycleState(),
            appForeground = true,
            nowElapsedMillis = 100L,
        )

    private fun resumed(
        backgroundedAt: Long,
        resumedAt: Long,
        initial: RadarForegroundLifecycleState = started(),
    ): RadarForegroundLifecycleState {
        val background = RadarForegroundResumeRefreshPolicy.observeLifecycle(
            initial,
            appForeground = false,
            nowElapsedMillis = backgroundedAt,
        )
        return RadarForegroundResumeRefreshPolicy.observeLifecycle(
            background,
            appForeground = true,
            nowElapsedMillis = resumedAt,
        )
    }

    private fun evaluable(
        freshness: RadarForegroundStreamFreshness,
    ) = RadarForegroundRefreshStreamState(
        RadarForegroundStreamReadiness.EVALUABLE,
        freshness,
    )

    private fun evaluate(
        state: RadarForegroundLifecycleState,
        nowElapsedMillis: Long,
        screenActive: Boolean = true,
        expectedStreams: Set<RadarForegroundRefreshStream> =
            setOf(RadarForegroundRefreshStream.RADAR),
        streams: Map<RadarForegroundRefreshStream, RadarForegroundRefreshStreamState> =
            mapOf(RadarForegroundRefreshStream.RADAR to radarState),
    ) = RadarForegroundResumeRefreshPolicy.consumeWhenRadarActive(
        state = state,
        screenActive = screenActive,
        nowElapsedMillis = nowElapsedMillis,
        expectedStreams = expectedStreams,
        streams = streams,
    )

    @Test fun `startup is not a resume and quick return reuses retained data`() {
        val startup = started()
        assertTrue(startup.hasObservedForeground)
        assertTrue(startup.appForeground)
        assertNull(startup.pendingTransition)

        val state = resumed(backgroundedAt = 120_000L, resumedAt = 180_000L)
        val decision = evaluate(state, nowElapsedMillis = 180_000L)
        assertEquals(state.pendingTransition, decision.transition)
        assertTrue(decision.dueStreams.isEmpty())

        val consumed = RadarForegroundResumeRefreshPolicy.markConsumed(
            state,
            requireNotNull(decision.transition).generation,
        )
        assertNull(consumed.pendingTransition)
    }

    @Test fun `lifecycle owner preserves a background edge missed by a suspended collector`() {
        val background = RadarForegroundResumeRefreshPolicy.observeLifecycle(
            started(),
            appForeground = false,
            nowElapsedMillis = 200_000L,
        )
        val latestStateSeenAfterCollectionRestarts =
            RadarForegroundResumeRefreshPolicy.observeLifecycle(
                background,
                appForeground = true,
                nowElapsedMillis = 400_000L,
            )

        assertTrue(latestStateSeenAfterCollectionRestarts.appForeground)
        assertEquals(1L, latestStateSeenAfterCollectionRestarts.pendingTransition?.generation)
        assertEquals(
            200_000L,
            latestStateSeenAfterCollectionRestarts.pendingTransition?.backgroundedAtElapsedMillis,
        )
        assertEquals(
            setOf(RadarForegroundRefreshStream.RADAR),
            evaluate(latestStateSeenAfterCollectionRestarts, 400_000L).dueStreams,
        )
    }

    @Test fun `crossing provider cadence and arrival grace refreshes exactly once`() {
        val dueAt = RadarForegroundResumeRefreshPolicy.nextOpportunityElapsedMillis(1_000L, 300L)
        assertEquals(346_000L, dueAt)
        val state = resumed(backgroundedAt = 200_000L, resumedAt = dueAt)
        val first = evaluate(state, nowElapsedMillis = dueAt)
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), first.dueStreams)

        val consumed = RadarForegroundResumeRefreshPolicy.markConsumed(
            state,
            requireNotNull(first.transition).generation,
        )
        val repeated = evaluate(consumed, nowElapsedMillis = dueAt + 60_000L)
        assertTrue(repeated.dueStreams.isEmpty())
        assertNull(repeated.transition)
    }

    @Test fun `resume on another page defers evaluation and background never polls`() {
        val foreground = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val otherPage = evaluate(foreground, nowElapsedMillis = 400_000L, screenActive = false)
        assertNull(otherPage.transition)
        assertEquals(1L, foreground.pendingTransition?.generation)

        val background = RadarForegroundResumeRefreshPolicy.observeLifecycle(
            started(), appForeground = false, nowElapsedMillis = 200_000L,
        ).copy(
            latestTransition = RadarForegroundTransition(1L, 200_000L, 400_000L),
        )
        val whileBackgrounded = evaluate(background, nowElapsedMillis = 500_000L)
        assertNull(whileBackgrounded.transition)
        assertFalse(background.appForeground)
        assertEquals(1L, background.pendingTransition?.generation)

        val entered = evaluate(foreground, nowElapsedMillis = 410_000L)
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), entered.dueStreams)
        assertEquals(400_000L, entered.transition?.resumedAtElapsedMillis)
    }

    @Test fun `temporary missing and waiting streams retain the transition until ready`() {
        val state = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val expected = setOf(
            RadarForegroundRefreshStream.RADAR,
            RadarForegroundRefreshStream.LIGHTNING,
        )
        val missing = evaluate(
            state,
            nowElapsedMillis = 400_000L,
            expectedStreams = expected,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to radarState),
        )
        assertNull(missing.transition)
        assertEquals(setOf(RadarForegroundRefreshStream.LIGHTNING), missing.waitingStreams)
        assertEquals(1L, state.pendingTransition?.generation)

        val explicitlyWaiting = evaluate(
            state,
            nowElapsedMillis = 400_000L,
            expectedStreams = expected,
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to radarState,
                RadarForegroundRefreshStream.LIGHTNING to RadarForegroundRefreshStreamState(
                    RadarForegroundStreamReadiness.WAITING,
                ),
            ),
        )
        assertEquals(
            setOf(RadarForegroundRefreshStream.LIGHTNING),
            explicitlyWaiting.waitingStreams,
        )

        val ready = evaluate(
            state,
            nowElapsedMillis = 400_000L,
            expectedStreams = expected,
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to radarState,
                RadarForegroundRefreshStream.LIGHTNING to evaluable(
                    RadarForegroundStreamFreshness(
                        acquiredAtElapsedMillis = 1_000L,
                        cadenceSeconds = 300L,
                    ),
                ),
            ),
        )
        assertEquals(expected, ready.dueStreams)
    }

    @Test fun `cold radar state without a session or attempt remains waiting`() {
        val state = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val cold = evaluate(
            state,
            nowElapsedMillis = 400_000L,
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to RadarForegroundRefreshStreamState(
                    RadarForegroundStreamReadiness.WAITING,
                ),
            ),
        )

        assertNull(cold.transition)
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), cold.waitingStreams)
        assertEquals(1L, state.pendingTransition?.generation)
    }

    @Test fun `enabled streams use independent cadence and disabled streams are omitted`() {
        val state = resumed(backgroundedAt = 100_000L, resumedAt = 700_000L)
        val expected = setOf(
            RadarForegroundRefreshStream.RADAR,
            RadarForegroundRefreshStream.CLOUDS,
            RadarForegroundRefreshStream.LIGHTNING,
        )
        val decision = evaluate(
            state,
            nowElapsedMillis = 700_000L,
            expectedStreams = expected,
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to radarState,
                RadarForegroundRefreshStream.CLOUDS to evaluable(
                    RadarForegroundStreamFreshness(
                        acquiredAtElapsedMillis = 500_000L,
                        cadenceSeconds = 600L,
                    ),
                ),
                RadarForegroundRefreshStream.LIGHTNING to evaluable(
                    RadarForegroundStreamFreshness(
                        acquiredAtElapsedMillis = 1_000L,
                        cadenceSeconds = 300L,
                    ),
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

    @Test fun `deterministically unavailable ancillary does not block an enabled radar stream`() {
        val state = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val decision = evaluate(
            state,
            nowElapsedMillis = 400_000L,
            expectedStreams = setOf(
                RadarForegroundRefreshStream.RADAR,
                RadarForegroundRefreshStream.LIGHTNING,
            ),
            streams = mapOf(
                RadarForegroundRefreshStream.RADAR to radarState,
                RadarForegroundRefreshStream.LIGHTNING to RadarForegroundRefreshStreamState(
                    RadarForegroundStreamReadiness.UNAVAILABLE,
                ),
            ),
        )
        assertEquals(setOf(RadarForegroundRefreshStream.RADAR), decision.dueStreams)
        assertTrue(decision.waitingStreams.isEmpty())
    }

    @Test fun `last attempt throttles a failed refresh until a later publication opportunity`() {
        val failedAttempt = evaluable(
            RadarForegroundStreamFreshness(
                acquiredAtElapsedMillis = 1_000L,
                lastAttemptAtElapsedMillis = 400_000L,
                cadenceSeconds = 300L,
            ),
        )
        val firstResume = resumed(backgroundedAt = 405_000L, resumedAt = 500_000L)
        val afterFailure = evaluate(
            firstResume,
            nowElapsedMillis = 500_000L,
            streams = mapOf(RadarForegroundRefreshStream.RADAR to failedAttempt),
        )
        assertTrue(afterFailure.dueStreams.isEmpty())

        val consumed = RadarForegroundResumeRefreshPolicy.markConsumed(
            firstResume,
            requireNotNull(afterFailure.transition).generation,
        )
        val laterResume = resumed(
            backgroundedAt = 600_000L,
            resumedAt = 745_000L,
            initial = consumed,
        )
        assertEquals(
            setOf(RadarForegroundRefreshStream.RADAR),
            evaluate(
                laterResume,
                nowElapsedMillis = 745_000L,
                streams = mapOf(RadarForegroundRefreshStream.RADAR to failedAttempt),
            ).dueStreams,
        )
    }

    @Test fun `successful replacement acquisition becomes the freshness anchor`() {
        val replaced = RadarForegroundStreamFreshness(
            acquiredAtElapsedMillis = 800_000L,
            lastAttemptAtElapsedMillis = 400_000L,
            cadenceSeconds = 300L,
        )

        assertEquals(800_000L, replaced.freshnessAnchorElapsedMillis)
        assertEquals(
            1_145_000L,
            RadarForegroundResumeRefreshPolicy.nextOpportunityElapsedMillis(
                requireNotNull(replaced.freshnessAnchorElapsedMillis),
                replaced.cadenceSeconds,
            ),
        )
    }

    @Test fun `stale acknowledgement cannot consume a newer resume generation`() {
        val first = resumed(backgroundedAt = 200_000L, resumedAt = 400_000L)
        val second = resumed(backgroundedAt = 500_000L, resumedAt = 700_000L, initial = first)
        assertEquals(2L, second.pendingTransition?.generation)

        val staleAck = RadarForegroundResumeRefreshPolicy.markConsumed(second, generation = 1L)
        assertEquals(2L, staleAck.pendingTransition?.generation)
        val currentAck = RadarForegroundResumeRefreshPolicy.markConsumed(staleAck, generation = 2L)
        assertNull(currentAck.pendingTransition)
    }
}
