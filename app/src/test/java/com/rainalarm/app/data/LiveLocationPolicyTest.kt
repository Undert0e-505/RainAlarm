package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLocationPolicyTest {
    @Test fun approximatePermissionKeepsNetworkUpdatesWithoutRequestingGps() {
        assertEquals(listOf("network"),
            LiveLocationPolicy.allowedProviders(listOf("network", "gps"), fineGranted = false))
        assertEquals(emptyList<String>(),
            LiveLocationPolicy.allowedProviders(listOf("gps"), fineGranted = false))
    }

    @Test fun precisePermissionCanUseBothEnabledSources() {
        assertEquals(listOf("network", "gps"),
            LiveLocationPolicy.allowedProviders(listOf("network", "gps"), fineGranted = true))
    }
}
