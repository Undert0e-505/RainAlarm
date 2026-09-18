package com.rainalarm.app.ui

import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveMapPolicyTest {
    private val selected = SavedPlace("Current", 51.5000, -0.1000, isCurrentLocation = true)
    private val smallFix = selected.copy(latitude = 51.5003, longitude = -0.1002)

    @Test fun freshSubMaterialFixMovesMarkerWithoutChangingForecastPlaceOrCameraWhenNotFollowing() {
        assertEquals(smallFix, RadarLiveMapPolicy.marker(selected, smallFix))
        assertEquals(51.5000, selected.latitude, 0.0)
        assertFalse(RadarLiveMapPolicy.shouldCenterOnFix(CURRENT_LOCATION_ID, smallFix, false))
        assertEquals(RadarLiveSessionPolicy.loadIdentity(selected),
            RadarLiveSessionPolicy.loadIdentity(smallFix))
    }

    @Test fun followCentersEachFreshFixAndStopsForUnavailableOrSavedPlace() {
        assertTrue(RadarLiveMapPolicy.shouldCenterOnFix(CURRENT_LOCATION_ID, selected, true))
        assertTrue(RadarLiveMapPolicy.shouldCenterOnFix(CURRENT_LOCATION_ID, smallFix, true))
        assertFalse(RadarLiveMapPolicy.shouldCenterOnFix(CURRENT_LOCATION_ID, null, true))
        val saved = SavedPlace("London", 51.5, -0.1)
        assertEquals(saved, RadarLiveMapPolicy.marker(saved, smallFix))
        assertFalse(RadarLiveMapPolicy.canFollow(saved.id, smallFix))
        assertFalse(RadarLiveMapPolicy.shouldCenterOnFix(saved.id, smallFix, true))
    }
}
