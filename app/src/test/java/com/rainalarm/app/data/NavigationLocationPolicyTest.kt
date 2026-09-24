package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLocationPolicyTest {
    private fun fix(
        lat: Double = 51.5,
        lon: Double = -0.1,
        wall: Long = 1_000_000L,
        elapsed: Long = 1_000_000_000L,
        accuracy: Float = 10f,
        source: LocationFixSource = LocationFixSource.FUSED,
        fine: Boolean = true,
    ) = NavigationLocationFix(lat, lon, wall, elapsed, accuracy, source, fine)

    @Test fun requestProfilesFollowForegroundCurrentAndFollowState() {
        assertEquals(LocationRequestMode.OFF, LocationRequestProfiles.select(false, true, true).mode)
        assertEquals(LocationRequestMode.OFF, LocationRequestProfiles.select(true, false, true).mode)
        assertEquals(LocationRequestProfiles.Current, LocationRequestProfiles.select(true, true, false))
        assertEquals(LocationRequestProfiles.Follow, LocationRequestProfiles.select(true, true, true))
        assertEquals(5_000L, LocationRequestProfiles.Current.intervalMillis)
        assertEquals(2_000L, LocationRequestProfiles.Current.fastestIntervalMillis)
        assertEquals(1_000L, LocationRequestProfiles.Follow.intervalMillis)
        assertEquals(500L, LocationRequestProfiles.Follow.fastestIntervalMillis)
        assertEquals(0L, LocationRequestProfiles.Follow.maxDelayMillis)
    }

    @Test fun precisePermissionAndAccuracyGateFollow() {
        assertTrue(FollowCapabilityPolicy.canFollow(true, LocationFixQuality.ACCURATE))
        assertFalse(FollowCapabilityPolicy.canFollow(false, LocationFixQuality.ACCURATE))
        assertFalse(FollowCapabilityPolicy.canFollow(true, LocationFixQuality.WEAK))
        assertTrue(FollowCapabilityPolicy.unavailableMessage(false, null).contains("Precise"))
    }

    @Test fun recentAccurateFixBeatsNewerCoarseFix() {
        val previous = fix()
        val coarse = fix(wall = 1_001_000, elapsed = 2_000_000_000, accuracy = 200f)
        assertTrue(NavigationFixAdjudicationPolicy.decide(
            coarse, previous, 1_001_000, 2_000_000_000,
        ) is LocationFixDecision.HoldPrevious)
    }

    @Test fun staleOutOfOrderAndImpossibleJumpAreRejectedButStalePriorCanRecover() {
        val previous = fix()
        assertTrue(NavigationFixAdjudicationPolicy.decide(
            fix(wall = 999_000, elapsed = 900_000_000), previous, 1_001_000, 2_000_000_000,
        ) is LocationFixDecision.Reject)
        assertTrue(NavigationFixAdjudicationPolicy.decide(
            fix(wall = 1_001_000, elapsed = 2_000_000_000, lat = 52.5), previous,
            1_001_000, 2_000_000_000,
        ) is LocationFixDecision.Reject)
        val recovered = NavigationFixAdjudicationPolicy.decide(
            fix(wall = 1_301_000, elapsed = 301_000_000_000, lat = 52.5, accuracy = 80f),
            previous, 1_301_000, 301_000_000_000,
        )
        assertTrue(recovered is LocationFixDecision.Accept)
        val stale = fix(wall = 1_000)
        assertTrue(NavigationFixAdjudicationPolicy.decide(
            stale, null, 1_000 + NavigationFixAdjudicationPolicy.maximumCandidateAgeMillis + 1,
            9_000_000_000,
        ) is LocationFixDecision.Reject)
    }

    @Test fun qualityMakesNetworkAndApproximateExplicitAndGraceIsBounded() {
        assertEquals(LocationFixQuality.PROVISIONAL,
            NavigationFixAdjudicationPolicy.quality(fix(source = LocationFixSource.NETWORK)))
        assertEquals(LocationFixQuality.APPROXIMATE,
            NavigationFixAdjudicationPolicy.quality(fix(fine = false)))
        val accepted = fix()
        assertTrue(NavigationFixAdjudicationPolicy.withinWeakSignalGrace(accepted, 10_000_000_000))
        assertFalse(NavigationFixAdjudicationPolicy.withinWeakSignalGrace(accepted, 20_000_000_000))
    }

    @Test fun followAnchorUsesKilometreOrTimedQuarterKilometreAndRegionBoundaryIsImmediate() {
        val first = fix()
        val nearSoon = fix(lat = 51.5027, wall = 1_060_000, elapsed = 61_000_000_000)
        val nearLate = nearSoon.copy(wallTimeMillis = 1_121_000, elapsedRealtimeNanos = 122_000_000_000)
        val farSoon = fix(lat = 51.5100, wall = 1_060_000, elapsed = 61_000_000_000)
        assertFalse(WeatherAnalysisAnchorPolicy.shouldAdvance(first, nearSoon, true, "uk", "uk"))
        assertTrue(WeatherAnalysisAnchorPolicy.shouldAdvance(first, nearLate, true, "uk", "uk"))
        assertTrue(WeatherAnalysisAnchorPolicy.shouldAdvance(first, farSoon, true, "uk", "uk"))
        assertTrue(WeatherAnalysisAnchorPolicy.shouldAdvance(first, nearSoon, true, "uk", "eu"))
        assertTrue(WeatherAnalysisAnchorPolicy.shouldAdvance(first, nearSoon, false, "uk", "uk"))
    }
}
