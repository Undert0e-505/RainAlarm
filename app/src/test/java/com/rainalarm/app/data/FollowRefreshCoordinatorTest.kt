package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowRefreshCoordinatorTest {
    private val now = 10_000L

    @Test fun providerCadenceUsesMedianAndSourceFallbacks() {
        assertEquals(300L, ProviderCadencePolicy.radarCadence(listOf(1_000, 1_300, 1_600, 1_900), true))
        assertEquals(ProviderCadencePolicy.regionalRadarFallbackSeconds,
            ProviderCadencePolicy.radarCadence(emptyList(), true))
        assertEquals(ProviderCadencePolicy.openRadarFallbackSeconds,
            ProviderCadencePolicy.radarCadence(emptyList(), false))
        assertEquals(10_645L, ProviderCadencePolicy.nextExpected(
            ProviderPublicationClock(10_000, 600),
        ))
        assertEquals(300L, ProviderCadencePolicy.radarCadence(
            listOf(1_000, 1_300, 1_600, 1_900, 3_100), true,
        ))
        assertEquals(900L, ProviderCadencePolicy.modelCadenceSeconds)
        assertEquals(listOf(60L, 120L, 300L), ProviderCadencePolicy.retryBackoffSeconds)
    }

    @Test fun unchangedAndFailureRetryAtOneTwoThenFiveMinutesWithoutOverlap() {
        val coordinator = FollowRefreshCoordinator()
        val clock = ProviderPublicationClock(9_000, 300)
        coordinator.synchronize(mapOf(FollowRefreshStream.RADAR_NOW to clock), now)
        val due = requireNotNull(coordinator.nextDue(FollowRefreshStream.RADAR_NOW))
        val first = requireNotNull(coordinator.start(FollowRefreshStream.RADAR_NOW, due))
        assertNull(coordinator.start(FollowRefreshStream.RADAR_NOW, due))
        assertTrue(coordinator.complete(first, 9_000, true, due))
        assertEquals(due + 60, coordinator.nextDue(FollowRefreshStream.RADAR_NOW))
        val second = requireNotNull(coordinator.start(FollowRefreshStream.RADAR_NOW, due + 60))
        assertTrue(coordinator.complete(second, null, false, due + 60))
        assertEquals(due + 180, coordinator.nextDue(FollowRefreshStream.RADAR_NOW))
        val third = requireNotNull(coordinator.start(FollowRefreshStream.RADAR_NOW, due + 180))
        coordinator.complete(third, 9_000, true, due + 180)
        assertEquals(due + 480, coordinator.nextDue(FollowRefreshStream.RADAR_NOW))
    }

    @Test fun advancementRebasesAndStaleGenerationCannotComplete() {
        val coordinator = FollowRefreshCoordinator()
        coordinator.synchronize(mapOf(
            FollowRefreshStream.CLOUDS to ProviderPublicationClock(9_000, 600),
        ), now)
        val due = requireNotNull(coordinator.nextDue(FollowRefreshStream.CLOUDS))
        val ticket = requireNotNull(coordinator.start(FollowRefreshStream.CLOUDS, due))
        assertTrue(coordinator.complete(ticket, 9_600, true, due))
        assertEquals(10_245L, coordinator.nextDue(FollowRefreshStream.CLOUDS))
        assertFalse(coordinator.complete(ticket, 10_200, true, due + 1))
    }

    @Test fun newerProviderClockSupersedesAnInFlightGeneration() {
        val coordinator = FollowRefreshCoordinator()
        val initial = ProviderPublicationClock(9_000, 300)
        coordinator.synchronize(mapOf(FollowRefreshStream.LIGHTNING to initial), now)
        val due = requireNotNull(coordinator.nextDue(FollowRefreshStream.LIGHTNING))
        val stale = requireNotNull(coordinator.start(FollowRefreshStream.LIGHTNING, due))
        coordinator.synchronize(mapOf(
            FollowRefreshStream.LIGHTNING to initial.copy(latestEpochSeconds = 9_300),
        ), due + 1)
        assertFalse(coordinator.complete(stale, 9_600, true, due + 2))
        assertFalse(coordinator.isInFlight(FollowRefreshStream.LIGHTNING))
        assertEquals(9_645L, coordinator.nextDue(FollowRefreshStream.LIGHTNING))
    }

    @Test fun cloudLightningWindAndPointClocksRemainIndependent() {
        val coordinator = FollowRefreshCoordinator()
        coordinator.synchronize(mapOf(
            FollowRefreshStream.CLOUDS to ProviderPublicationClock(9_000, 600),
            FollowRefreshStream.LIGHTNING to ProviderPublicationClock(9_000, 300),
            FollowRefreshStream.WIND to ProviderPublicationClock(9_000, 900),
            FollowRefreshStream.POINT_WEATHER to ProviderPublicationClock(9_000, 900),
        ), now)
        assertEquals(10_000L, coordinator.nextDue(FollowRefreshStream.LIGHTNING))
        assertEquals(10_000L, coordinator.nextDue(FollowRefreshStream.CLOUDS))
        assertEquals(10_000L, coordinator.nextDue(FollowRefreshStream.WIND))
        assertEquals(10_000L, coordinator.nextDue(FollowRefreshStream.POINT_WEATHER))
        val lightning = requireNotNull(coordinator.start(FollowRefreshStream.LIGHTNING, now))
        assertTrue(coordinator.isInFlight(FollowRefreshStream.LIGHTNING))
        assertFalse(coordinator.isInFlight(FollowRefreshStream.CLOUDS))
        assertTrue(coordinator.complete(lightning, 9_300, true, now))
    }

    @Test fun disabledLayersDisappearAndPauseStopsAllWork() {
        val coordinator = FollowRefreshCoordinator()
        coordinator.synchronize(mapOf(
            FollowRefreshStream.CLOUDS to ProviderPublicationClock(9_000, 600, false),
            FollowRefreshStream.LIGHTNING to ProviderPublicationClock(9_000, 300, true),
            FollowRefreshStream.WIND to ProviderPublicationClock(9_000, 900, true),
        ), now)
        assertNull(coordinator.nextDue(FollowRefreshStream.CLOUDS))
        assertTrue(coordinator.nextDue(FollowRefreshStream.LIGHTNING) != null)
        coordinator.pause()
        assertTrue(FollowRefreshStream.entries.all { coordinator.nextDue(it) == null })
    }

    @Test fun manualRefreshRebasesEveryEnabledStreamWithoutStartingOne() {
        val coordinator = FollowRefreshCoordinator()
        val clocks = mapOf(
            FollowRefreshStream.RADAR_NOW to ProviderPublicationClock(9_900, 300),
            FollowRefreshStream.POINT_WEATHER to ProviderPublicationClock(9_900, 900),
        )
        coordinator.manualRebase(clocks, now)
        assertEquals(10_245L, coordinator.nextDue(FollowRefreshStream.RADAR_NOW))
        assertEquals(10_845L, coordinator.nextDue(FollowRefreshStream.POINT_WEATHER))
        assertFalse(coordinator.isInFlight(FollowRefreshStream.RADAR_NOW))
    }
}
