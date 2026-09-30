package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.SavedPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveSessionPolicyTest {
    private fun current(lat: Double, lon: Double) = SavedPlace("Current", lat, lon, isCurrentLocation = true)

    @Test fun currentFixesWithinRegionKeepCachedSessionAcrossMaterialMovement() {
        val loaded = current(53.4808, -2.2426)
        assertTrue(RadarLiveSessionPolicy.canReuse(loaded, current(53.486, -2.238), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded, current(52.5200, 13.4050), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(51.70, 0.50), "nl",
        )) // stale pre-fix NL session must reload even without a new fix
        assertTrue(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(51.71, 0.51), "uk",
        ))
        assertFalse(RadarLiveSessionPolicy.canReuse(
            current(51.70, 0.50), current(52.37, 4.90), "uk",
        ))
    }

    @Test fun firstResolvedVirtualCurrentFixStartsLoadButLaterMovementDoesNotCancelIt() {
        val initial = current(53.4808, -2.2426)
        assertNull(RadarLiveSessionPolicy.loadIdentity(null))
        assertEquals("current-location", RadarLiveSessionPolicy.loadIdentity(initial))
        assertEquals(RadarLiveSessionPolicy.loadIdentity(initial),
            RadarLiveSessionPolicy.loadIdentity(current(53.486, -2.238)))
    }

    @Test fun fiveHertzTravelFixesUseOneRegionalAcquisitionKey() {
        val fixes = (0 until 25).map { index ->
            current(51.5074 + index * 0.00001, -0.1278 + index * 0.00001)
        }
        assertEquals(1, fixes.map(RadarLiveSessionPolicy::acquisitionLocationKey).distinct().size)
        assertEquals("region:uk", RadarLiveSessionPolicy.acquisitionLocationKey(fixes.first()))
        assertEquals(
            "region:nl",
            RadarLiveSessionPolicy.acquisitionLocationKey(current(52.3676, 4.9041)),
        )
    }

    @Test fun effectiveSelectionChangesUseCoordinateToleranceThenCurrentUsesCoverage() {
        val loaded = current(53.4808, -2.2426)
        assertTrue(RadarLiveSessionPolicy.canReuse(loaded, current(53.486, -2.238), null))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded, current(53.54, -2.24), null))
        val saved = SavedPlace("Saved", 53.4808, -2.2426)
        val equivalentCurrent = current(53.4810, -2.2426)
        val materiallyDifferentCurrent = current(53.49, -2.24)
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(saved, equivalentCurrent))
        assertFalse(RadarLiveSessionPolicy.selectionCompatible(saved, materiallyDifferentCurrent))
        // Once Current is adopted as the acquisition anchor, rapid movement within UK remains
        // coverage-compatible rather than being compared with the old Saved coordinate.
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(equivalentCurrent,
            current(54.0, -2.0)))
        assertTrue(RadarLiveSessionPolicy.canReuse(equivalentCurrent,
            current(54.0, -2.0), "uk"))
        assertFalse(RadarLiveSessionPolicy.canReuse(equivalentCurrent,
            current(52.52, 13.405), "uk"))
    }

    @Test fun travelModeAndPlaceIdentityAreNotRainAcquisitionInputs() {
        val saved = SavedPlace("Saved", 51.5074, -0.1278)
        val liveSame = current(51.5075, -0.1278)
        val savedIdentity = RadarRainSessionLoadPolicy.identity(
            saved, RadarProviderKind.METEOGROUP_REGIONAL, false,
        )
        val liveIdentity = RadarRainSessionLoadPolicy.identity(
            liveSame, RadarProviderKind.METEOGROUP_REGIONAL, false,
        )
        assertEquals(savedIdentity, liveIdentity)
        assertTrue(RadarLiveSessionPolicy.selectionCompatible(saved, liveSame))
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, liveIdentity, savedIdentity, 3, 3, hasReusableSession = true,
        )) // Saved -> Travel at the same effective coordinate: zero requests.
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, savedIdentity, liveIdentity, 3, 3, hasReusableSession = true,
        )) // Travel -> Saved at the same effective coordinate: zero requests.
    }

    @Test fun travelRequestMatrixReloadsOnlyForEffectiveInputOrCoverageChanges() {
        val saved = SavedPlace("Saved", 51.5074, -0.1278)
        val sameLive = current(51.5075, -0.1278)
        val movedLive = current(53.4808, -2.2426)
        val outside = current(52.52, 13.405)
        val identity = requireNotNull(RadarRainSessionLoadPolicy.identity(
            saved, RadarProviderKind.METEOGROUP_REGIONAL, false,
        ))
        fun requestCount(anchor: SavedPlace, selected: SavedPlace, regionId: String): Int {
            val reusable = RadarLiveSessionPolicy.selectionCompatible(anchor, selected) &&
                RadarLiveSessionPolicy.canReuse(anchor, selected, regionId)
            return if (RadarRainSessionLoadPolicy.shouldLoad(
                    true, identity, identity, 8, 8, reusable,
                )) 1 else 0
        }

        assertEquals(0, requestCount(saved, sameLive, "uk"))
        assertEquals(1, requestCount(saved, movedLive, "uk"))
        // After equivalent activation adopts Current, ordinary rapid movement is regional reuse.
        assertEquals(0, requestCount(sameLive, movedLive, "uk"))
        assertEquals(1, requestCount(movedLive, saved, "uk"))
        assertEquals(1, requestCount(movedLive, outside, "uk"))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, identity, identity, 9, 8, hasReusableSession = true,
        )) // manual/publication generation
        val otherProvider = requireNotNull(RadarRainSessionLoadPolicy.identity(
            sameLive, RadarProviderKind.OPEN_RAINVIEWER, false,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, otherProvider, identity, 8, 8, hasReusableSession = true,
        ))
    }

    @Test fun screenReentryReusesFreshRainButRealInvalidationsLoad() {
        val place = SavedPlace("London", 51.5074, -0.1278)
        val meteo = requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.METEOGROUP_REGIONAL, showLikelySnow = false,
        ))
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, meteo, 4, 4, hasReusableSession = true,
        )) // repeated Now <-> Radar entry
        assertFalse(RadarRainSessionLoadPolicy.shouldLoad(
            false, meteo, null, 0, Int.MIN_VALUE, hasReusableSession = false,
        ))
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, null, 0, Int.MIN_VALUE, hasReusableSession = false,
        )) // cold / failed / incomplete session
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, meteo, meteo, 5, 4, hasReusableSession = true,
        )) // manual refresh or publication-aware Travel refresh

        val opera = RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.EUMETNET_OPERA, showLikelySnow = false,
        )
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, opera, meteo, 4, 4, hasReusableSession = true,
        )) // provider change
        val elsewhere = RadarRainSessionLoadPolicy.identity(
            SavedPlace("Paris", 48.8566, 2.3522),
            RadarProviderKind.METEOGROUP_REGIONAL,
            showLikelySnow = false,
        )
        assertTrue(RadarRainSessionLoadPolicy.shouldLoad(
            true, elsewhere, meteo, 4, 4, hasReusableSession = false,
        )) // place / coverage change
    }

    @Test fun snowSettingRemainsAnExplicitRainConfigurationInvalidation() {
        val place = SavedPlace("London", 51.5074, -0.1278)
        assertFalse(
            RadarRainSessionLoadPolicy.identity(
                place, RadarProviderKind.METEOGROUP_REGIONAL, false,
            ) ==
            RadarRainSessionLoadPolicy.identity(
                place, RadarProviderKind.METEOGROUP_REGIONAL, true,
            ),
        )
        assertFalse(requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.OPEN_RAINVIEWER, false,
        )).showLikelySnow)
        assertTrue(requireNotNull(RadarRainSessionLoadPolicy.identity(
            place, RadarProviderKind.OPEN_RAINVIEWER, true,
        )).showLikelySnow)
    }
}
