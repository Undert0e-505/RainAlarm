package com.rainalarm.app.ui

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

    @Test fun openSessionOnlyReusesNearbyLiveFixesAndSavedSelectionNeverAliases() {
        val loaded = current(53.4808, -2.2426)
        assertTrue(RadarLiveSessionPolicy.canReuse(loaded, current(53.486, -2.238), null))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded, current(53.54, -2.24), null))
        assertFalse(RadarLiveSessionPolicy.canReuse(loaded,
            SavedPlace("Saved", 53.4808, -2.2426), "uk"))
    }
}
