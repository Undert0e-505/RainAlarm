package com.rainalarm.app.ui

import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.RadarProviderKind
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

    @Test fun followCameraDurationTracksFixIntervalWithinSmoothBounds() {
        assertEquals(1_000, RadarFollowCameraPolicy.durationMillis(0, 1_000_000_000))
        assertEquals(500, RadarFollowCameraPolicy.durationMillis(1_000_000_000, 1_200_000_000))
        assertEquals(1_000, RadarFollowCameraPolicy.durationMillis(1_000_000_000, 2_000_000_000))
        assertEquals(1_200, RadarFollowCameraPolicy.durationMillis(1_000_000_000, 4_000_000_000))
    }

    @Test fun playbackIdentitySurvivesSessionRefreshButChangesForPlaceOrProvider() {
        val before = RadarPlaybackRefreshPolicy.identity(
            CURRENT_LOCATION_ID,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val refreshed = RadarPlaybackRefreshPolicy.identity(
            CURRENT_LOCATION_ID,
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val newPlace = RadarPlaybackRefreshPolicy.identity(
            "saved-place",
            RadarProviderKind.METEOGROUP_REGIONAL,
        )
        val newProvider = RadarPlaybackRefreshPolicy.identity(
            CURRENT_LOCATION_ID,
            RadarProviderKind.OPEN_RAINVIEWER,
        )

        assertEquals(before, refreshed)
        assertFalse(before == newPlace)
        assertFalse(before == newProvider)
    }
}
