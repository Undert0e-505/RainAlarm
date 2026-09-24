package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveMapWiringTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/rainalarm/app/ui/$name"),
        File("app/src/main/java/com/rainalarm/app/ui/$name"),
    ).first(File::isFile).readText()

    @Test fun followControlIsCurrentOnlyInTopRowAndHasAccessibleStates() {
        val screen = source("RadarScreen.kt")
        val topRow = screen.substringAfter(
            "Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically)",
        ).substringBefore("RadarLayerSegments(enabledMapLayers")
        assertTrue(topRow.contains("if (selectedPlaceId == CURRENT_LOCATION_ID) {"))
        assertTrue(topRow.contains("IconButton(onClick = { onFollowLiveChange(!followLive) }"))
        assertTrue(screen.contains("Stop following live location"))
        assertTrue(screen.contains("Follow live location"))
        assertTrue(screen.contains("enabled = hasFreshLiveFix || followCapability.message != null"))
        assertTrue(!topRow.contains("RadarLayerSegments"))
    }

    @Test fun liveMarkerUsesLightweightProjectionWithoutReplanningRasterMesh() {
        val map = source("RadarImageMap.kt")
        val gl = source("LegacyRadarGlOverlayView.kt")
        assertTrue(map.contains("activeRadarSlot?.setMarker(markerPlace)"))
        assertTrue(map.contains("pendingRadarSlot?.setMarker(markerPlace)"))
        assertTrue(map.contains("takeIf { it.session === session }"))
        assertTrue(map.contains("baseMarkerView.update(markerPlace)"))
        assertTrue(map.contains("baseMarkerView.onCameraMoved()"))
        assertTrue(map.contains("if (!followLive || teardown.isClosed)"))
        assertTrue(map.contains("ready.cancelTransitions()"))
        assertTrue(map.contains("ready.easeCamera(CameraUpdateFactory.newCameraPosition(northUpCamera(target)), duration)"))
        assertTrue(map.contains("REASON_API_GESTURE"))
        assertTrue(gl.contains("if (moved) updateMarkerProjection(mapPlace)"))
    }
}
