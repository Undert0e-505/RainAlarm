package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationPolicyTest {
    @Test fun foregroundCallbacksCanMoveMarkerBeforeForecastMaterialThreshold() {
        assertEquals(5_000L, LiveLocationPolicy.UPDATE_INTERVAL_MILLIS)
        assertEquals(0f, LiveLocationPolicy.UPDATE_DISTANCE_METRES)
        assertFalse(LiveLocationPolicy.materiallyMoved(51.5000, -0.1000, 51.5003, -0.1002))
        assertFalse(LiveLocationPolicy.isNewerFix(1000L, 1000L))
        assertFalse(LiveLocationPolicy.isNewerFix(999L, 1000L))
        assertTrue(LiveLocationPolicy.isNewerFix(1001L, 1000L))
    }
    @Test fun approximatePermissionKeepsNetworkUpdatesWithoutRequestingGps() {
        assertEquals(listOf("network"),
            LiveLocationPolicy.allowedProviders(listOf("network", "gps"), fineGranted = false))
        assertEquals(emptyList<String>(),
            LiveLocationPolicy.allowedProviders(listOf("gps"), fineGranted = false))
    }

    @Test fun precisePermissionUsesGpsWithoutACompetingNetworkStream() {
        assertEquals(listOf("gps"),
            LiveLocationPolicy.allowedProviders(listOf("network", "gps"), fineGranted = true))
    }
}
