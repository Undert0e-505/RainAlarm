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
        speed: Float? = null,
        bearing: Float? = null,
        speedAccuracy: Float? = null,
        bearingAccuracy: Float? = null,
    ) = NavigationLocationFix(
        lat, lon, wall, elapsed, accuracy, source, fine,
        speed, bearing, speedAccuracy, bearingAccuracy,
    )

    @Test fun requestProfilesFollowForegroundCurrentAndFollowState() {
        assertEquals(LocationRequestMode.OFF, LocationRequestProfiles.select(false, true, true).mode)
        assertEquals(LocationRequestMode.OFF, LocationRequestProfiles.select(true, false, true).mode)
        assertEquals(LocationRequestProfiles.Current, LocationRequestProfiles.select(true, true, false))
        assertEquals(LocationRequestProfiles.Follow, LocationRequestProfiles.select(true, true, true))
        assertEquals(5_000L, LocationRequestProfiles.Current.intervalMillis)
        assertEquals(2_000L, LocationRequestProfiles.Current.fastestIntervalMillis)
        assertEquals(200L, LocationRequestProfiles.Follow.intervalMillis)
        assertEquals(200L, LocationRequestProfiles.Follow.fastestIntervalMillis)
        assertEquals(0L, LocationRequestProfiles.Follow.maxDelayMillis)
        assertEquals(0f, LocationRequestProfiles.Follow.minimumDistanceMetres, 0f)
        assertTrue(LocationRequestProfiles.Follow.requiresFinePermission)
    }

    @Test fun rapidOrderedTravelFixesAreAcceptedWithoutAnArtificialCadenceGate() {
        val previous = fix()
        val rapid = fix(
            lat = 51.50001,
            wall = 1_000_200L,
            elapsed = 1_200_000_000L,
        )
        assertTrue(NavigationFixAdjudicationPolicy.decide(
            rapid, previous, 1_000_200L, 1_200_000_000L,
        ) is LocationFixDecision.Accept)
    }

    @Test fun twoHundredMillisecondFixStreamReachesFiveAcceptedUpdatesPerSecond() {
        val cadence = LocationCadenceAccumulator()
        var previous: NavigationLocationFix? = null
        repeat(16) { index ->
            val elapsed = 1_000_000_000L + index * 200_000_000L
            val candidate = fix(
                lat = 51.5 + index * 0.000001,
                wall = 1_000_000L + index * 200L,
                elapsed = elapsed,
            )
            val decision = NavigationFixAdjudicationPolicy.decide(
                candidate,
                previous,
                candidate.wallTimeMillis,
                elapsed,
            )
            assertTrue(decision is LocationFixDecision.Accept)
            previous = candidate
            cadence.record(elapsed)
        }
        assertEquals(5, cadence.countBetween(1_000_000_000L, 2_000_000_000L))
        assertEquals(5, cadence.countBetween(2_000_000_000L, 3_000_000_000L))
        assertEquals(5, cadence.countBetween(3_000_000_000L, 4_000_000_000L))
    }

    @Test fun oneHertzPlatformMotionProducesBoundedTwoHundredMillisecondVisualTargets() {
        val tracker = TravelVisualMotionTracker()
        val sourceElapsed = 1_000_000_000L
        tracker.accept(fix(
            lat = 51.5,
            lon = -0.1,
            elapsed = sourceElapsed,
            speed = 10f,
            bearing = 90f,
            speedAccuracy = 0.5f,
            bearingAccuracy = 5f,
        ))

        val targets = (0L..800L step 200L).map { offsetMillis ->
            requireNotNull(tracker.target(sourceElapsed + offsetMillis * 1_000_000L))
        }

        assertEquals(listOf(0L, 200L, 400L, 600L, 800L), targets.map {
            (it.displayElapsedRealtimeNanos - sourceElapsed) / 1_000_000L
        })
        assertTrue(targets.drop(1).zipWithNext().all { (before, after) ->
            distanceMetres(before.latitude, before.longitude, after.latitude, after.longitude) > 1.8
        })
        assertTrue(targets.drop(1).all { it.projected })
        assertTrue(targets.all { it.motionSource == TravelVisualMotionSource.PLATFORM })
    }

    @Test fun fiveHertzRealFixesRemainThePresentationSourceWithoutCoalescing() {
        val tracker = TravelVisualMotionTracker()
        val targets = (0..5).map { index ->
            val elapsed = 1_000_000_000L + index * 200_000_000L
            val eastwardDegrees = index * 0.000029
            tracker.accept(fix(
                lon = -0.1 + eastwardDegrees,
                wall = 1_000_000L + index * 200L,
                elapsed = elapsed,
                speed = 10f,
                bearing = 90f,
            ))
            requireNotNull(tracker.target(elapsed))
        }
        assertEquals(6, targets.map { it.sourceFixElapsedRealtimeNanos }.distinct().size)
        assertEquals(
            (0..5).map { 1_000_000_000L + it * 200_000_000L },
            targets.map { it.sourceFixElapsedRealtimeNanos },
        )
        assertTrue(targets.drop(1).all { it.motionSource == TravelVisualMotionSource.PLATFORM })
    }

    @Test fun sparseFixesWithoutMotionMetadataProduceCausalFiveHertzInterpolation() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(
            lon = -0.1,
            elapsed = 1_000_000_000L,
            accuracy = 2f,
        )
        val second = fix(
            lon = -0.09998,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = first.elapsedRealtimeNanos + 1_000_000_000L,
            accuracy = 2f,
        )
        tracker.accept(first)
        val start = requireNotNull(tracker.accept(second))
        val targets = (200L..800L step 200L).map { offsetMillis ->
            requireNotNull(tracker.target(second.elapsedRealtimeNanos + offsetMillis * 1_000_000L))
        }
        val settled = requireNotNull(tracker.target(second.elapsedRealtimeNanos + 850_000_000L))

        assertEquals(first.longitude, start.longitude, 1e-10)
        assertTrue(targets.all { it.motionSource == TravelVisualMotionSource.INTERPOLATED })
        assertTrue((listOf(start) + targets + settled).zipWithNext().all { (before, after) ->
            after.longitude >= before.longitude
        })
        assertTrue(targets.all { it.longitude in first.longitude..second.longitude })
        assertEquals(second.longitude, settled.longitude, 1e-10)
        assertFalse(settled.projected)
    }

    @Test fun newerSparseFixRetargetsFromCurrentInterpolationWithoutSnapOrOvershoot() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(lon = -0.1, elapsed = 1_000_000_000L, accuracy = 2f)
        val second = fix(
            lon = -0.09998,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = 2_000_000_000L,
            accuracy = 2f,
        )
        tracker.accept(first)
        tracker.accept(second)
        val beforeRetarget = requireNotNull(tracker.target(2_500_000_000L))
        val third = fix(
            lon = -0.09996,
            wall = second.wallTimeMillis + 500L,
            elapsed = 2_500_000_000L,
            accuracy = 2f,
        )
        val retargeted = requireNotNull(tracker.accept(third))
        val advancing = requireNotNull(tracker.target(2_700_000_000L))
        val settled = requireNotNull(tracker.target(2_925_000_000L))

        assertEquals(beforeRetarget.latitude, retargeted.latitude, 1e-10)
        assertEquals(beforeRetarget.longitude, retargeted.longitude, 1e-10)
        assertTrue(advancing.longitude > retargeted.longitude)
        assertTrue(advancing.longitude < third.longitude)
        assertEquals(third.longitude, settled.longitude, 1e-10)
    }

    @Test fun staleOrImplausiblyLargeInterpolationTargetResetsToMeasuredFix() {
        val staleTracker = TravelVisualMotionTracker()
        val first = fix(lon = -0.1, elapsed = 1_000_000_000L, accuracy = 2f)
        staleTracker.accept(first)
        val stale = fix(
            lon = -0.09998,
            wall = first.wallTimeMillis + 4_000L,
            elapsed = 5_000_000_000L,
            accuracy = 2f,
        )
        val staleReset = requireNotNull(staleTracker.accept(stale))
        assertEquals(stale.longitude, staleReset.longitude, 1e-10)

        val jumpTracker = TravelVisualMotionTracker()
        jumpTracker.accept(first)
        val jump = fix(
            lat = first.latitude + 0.01,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = 2_000_000_000L,
            accuracy = 2f,
        )
        val jumpReset = requireNotNull(jumpTracker.accept(jump))
        assertEquals(jump.latitude, jumpReset.latitude, 1e-10)
        assertEquals(jump.longitude, jumpReset.longitude, 1e-10)
    }

    @Test fun clearingTrackerDropsAnActiveInterpolationBeforeNextFirstFix() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(lon = -0.1, elapsed = 1_000_000_000L, accuracy = 2f)
        tracker.accept(first)
        tracker.accept(fix(
            lon = -0.09998,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = 2_000_000_000L,
            accuracy = 2f,
        ))
        tracker.clear()
        assertTrue(tracker.target(2_400_000_000L) == null)
        val restarted = fix(lat = 52.0, lon = 0.2, elapsed = 3_000_000_000L)
        val target = requireNotNull(tracker.accept(restarted))
        assertEquals(restarted.latitude, target.latitude, 1e-10)
        assertEquals(restarted.longitude, target.longitude, 1e-10)
    }

    @Test fun projectionFreezesAtBoundAndPoorFixNeverProjects() {
        val tracker = TravelVisualMotionTracker()
        val sourceElapsed = 1_000_000_000L
        tracker.accept(fix(
            elapsed = sourceElapsed,
            speed = 20f,
            bearing = 0f,
        ))
        val atBound = requireNotNull(tracker.target(
            sourceElapsed + TravelVisualMotionPolicy.maximumProjectionMillis * 1_000_000L,
        ))
        val longAfter = requireNotNull(tracker.target(sourceElapsed + 10_000_000_000L))
        assertEquals(atBound.latitude, longAfter.latitude, 1e-10)
        assertEquals(atBound.longitude, longAfter.longitude, 1e-10)

        val beforePoor = requireNotNull(tracker.target(2_000_000_000L))
        tracker.accept(fix(
            lat = 52.0,
            lon = 0.2,
            wall = 1_001_000L,
            elapsed = 2_000_000_000L,
            accuracy = 100f,
            speed = 20f,
            bearing = 0f,
        ))
        val poorLater = requireNotNull(tracker.target(2_800_000_000L))
        assertEquals(beforePoor.latitude, poorLater.latitude, 1e-10)
        assertEquals(beforePoor.longitude, poorLater.longitude, 1e-10)
        assertFalse(poorLater.projected)
    }

    @Test fun stationaryJitterIsSuppressedAndMotionIsNotInvented() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(elapsed = 1_000_000_000L, speed = 0.2f, bearing = 120f)
        val initial = requireNotNull(tracker.accept(first))
        val jitter = fix(
            lat = first.latitude + 0.000004,
            lon = first.longitude - 0.000003,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = first.elapsedRealtimeNanos + 1_000_000_000L,
            speed = 0.1f,
            bearing = 20f,
        )
        val held = requireNotNull(tracker.accept(jitter))
        val later = requireNotNull(tracker.target(jitter.elapsedRealtimeNanos + 800_000_000L))
        assertEquals(initial.latitude, held.latitude, 1e-10)
        assertEquals(initial.longitude, held.longitude, 1e-10)
        assertEquals(held.latitude, later.latitude, 1e-10)
        assertEquals(held.longitude, later.longitude, 1e-10)
        assertEquals(TravelVisualMotionSource.STATIONARY, later.motionSource)
        assertFalse(later.projected)
    }

    @Test fun derivedMotionRequiresCredibleAccurateMovement() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(lat = 51.5, lon = -0.1, elapsed = 1_000_000_000L)
        tracker.accept(first)
        val second = fix(
            lat = 51.500135,
            lon = -0.1,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = 2_000_000_000L,
        )
        tracker.accept(second)
        val projected = requireNotNull(tracker.target(2_400_000_000L))
        assertEquals(TravelVisualMotionSource.DERIVED, projected.motionSource)
        assertTrue(projected.projected)
        assertTrue(projected.latitude > first.latitude)
    }

    @Test fun realFixCorrectionIsContinuousAndSettlesWithoutSnapBack() {
        val tracker = TravelVisualMotionTracker()
        val first = fix(
            lat = 51.5, lon = -0.1, elapsed = 1_000_000_000L,
            speed = 10f, bearing = 90f,
        )
        tracker.accept(first)
        val before = requireNotNull(tracker.target(2_000_000_000L))
        val next = fix(
            lat = 51.5,
            lon = -0.09982,
            wall = first.wallTimeMillis + 1_000L,
            elapsed = 2_000_000_000L,
            speed = 10f,
            bearing = 90f,
        )
        val reconciled = requireNotNull(tracker.accept(next))
        assertTrue(distanceMetres(
            before.latitude, before.longitude, reconciled.latitude, reconciled.longitude,
        ) < 0.2)
        val mid = requireNotNull(tracker.target(2_400_000_000L))
        val settled = requireNotNull(tracker.target(2_600_000_000L))
        assertTrue(distanceMetres(
            reconciled.latitude, reconciled.longitude, mid.latitude, mid.longitude,
        ) > 0.5)
        assertTrue(distanceMetres(mid.latitude, mid.longitude, settled.latitude, settled.longitude) > 0.5)
    }

    @Test fun presentationTickAlignsToMonotonicTwoHundredMillisecondBoundaries() {
        assertEquals(200L, TravelVisualMotionPolicy.delayUntilNextTickMillis(1_000_000_000L))
        assertEquals(150L, TravelVisualMotionPolicy.delayUntilNextTickMillis(1_050_000_000L))
        assertEquals(1L, TravelVisualMotionPolicy.delayUntilNextTickMillis(1_199_999_999L))
    }

    @Test fun causalInterpolationDurationCoversMostOfSparseCadenceWithinBounds() {
        val first = fix(elapsed = 1_000_000_000L)
        assertEquals(200L, TravelVisualMotionPolicy.interpolationDurationMillis(
            first,
            fix(wall = first.wallTimeMillis + 200L, elapsed = 1_200_000_000L),
        ))
        assertEquals(850L, TravelVisualMotionPolicy.interpolationDurationMillis(
            first,
            fix(wall = first.wallTimeMillis + 1_000L, elapsed = 2_000_000_000L),
        ))
        assertEquals(1_000L, TravelVisualMotionPolicy.interpolationDurationMillis(
            first,
            fix(wall = first.wallTimeMillis + 2_000L, elapsed = 3_000_000_000L),
        ))
        assertEquals(0L, TravelVisualMotionPolicy.interpolationDurationMillis(
            first,
            fix(wall = first.wallTimeMillis + 4_000L, elapsed = 5_000_000_000L),
        ))
    }

    @Test fun precisePermissionAndAccuracyGateFollow() {
        assertTrue(FollowCapabilityPolicy.canFollow(true, LocationFixQuality.ACCURATE))
        assertFalse(FollowCapabilityPolicy.canFollow(false, LocationFixQuality.ACCURATE))
        assertFalse(FollowCapabilityPolicy.canFollow(true, LocationFixQuality.WEAK))
        assertEquals(FollowCapabilityReason.PRECISE_PERMISSION,
            FollowCapabilityPolicy.unavailableReason(false, null))
        assertEquals(FollowCapabilityReason.WAITING_FOR_FIX,
            FollowCapabilityPolicy.unavailableReason(true, null))
        assertEquals(FollowCapabilityReason.WEAK_ACCURACY,
            FollowCapabilityPolicy.unavailableReason(true, LocationFixQuality.WEAK))
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
