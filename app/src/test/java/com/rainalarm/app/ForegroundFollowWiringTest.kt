package com.rainalarm.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundFollowWiringTest {
    private fun source(path: String): String = listOf(
        File(path), File("app/$path"),
    ).first(File::isFile).readText()

    @Test fun fusedLocationIsPrimaryWithExplicitGpsFallbackAndNoCoordinateLogging() {
        val location = source("src/main/java/com/rainalarm/app/data/PlatformLocationClient.kt")
        assertTrue(location.contains("FusedLocationProviderClient"))
        assertTrue(location.contains("getCurrentLocation(currentRequest"))
        assertTrue(location.contains("LocationSettingsRequest.Builder()"))
        assertTrue(location.contains("LocationManager.GPS_PROVIDER"))
        assertTrue(location.contains("profile.mode != LocationRequestMode.FOLLOW"))
        assertFalse(location.contains("Log."))
    }

    @Test fun followOwnsHighRateProfileSmoothNorthUpCameraAndScreenOnOnlyWhileVisible() {
        val main = source("src/main/java/com/rainalarm/app/MainActivity.kt")
        val screen = source("src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        val map = source("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt")
        assertTrue(main.contains("_followRequested"))
        assertTrue(main.contains("LocationRequestProfiles.select"))
        assertTrue(screen.contains("appForeground && selectedPlaceId == CURRENT_LOCATION_ID && followLive"))
        assertTrue(screen.contains("radarView.keepScreenOn = keepScreenOn"))
        assertTrue(screen.contains("onDispose { if (keepScreenOn) radarView.keepScreenOn = false }"))
        assertTrue(map.contains("ready.cancelTransitions()"))
        assertTrue(map.contains("ready.easeCamera"))
        assertTrue(map.contains(".bearing(0.0)"))
        assertTrue(map.contains(".tilt(0.0)"))
        assertTrue(map.contains("REASON_API_GESTURE"))
    }

    @Test fun coordinatorIsOneVisibleFollowLoopWithIndependentLayerRefreshes() {
        val screen = source("src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        assertTrue(screen.contains("val refreshCoordinator = remember { FollowRefreshCoordinator() }"))
        assertTrue(screen.contains("while (true)"))
        assertTrue(screen.contains("refreshCoordinator.due(now).forEach"))
        assertTrue(screen.contains("pendingCloudsRefresh"))
        assertTrue(screen.contains("pendingLightningRefresh"))
        assertTrue(screen.contains("pendingWindRefresh"))
        assertTrue(screen.contains("refreshCoordinator.pause()"))
        assertFalse(screen.contains("var layerRefresh"))
    }

    @Test fun appSourceAddsNoBackgroundLocationOrFollowWakeLockService() {
        val manifest = source("src/main/AndroidManifest.xml")
        val screen = source("src/main/java/com/rainalarm/app/ui/RadarScreen.kt")
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(manifest.contains("WAKE_LOCK"))
        assertFalse(manifest.contains("<service"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("tools:node='remove'"))
        assertFalse(screen.contains("PowerManager"))
        assertFalse(screen.contains("WakeLock"))
    }
}
