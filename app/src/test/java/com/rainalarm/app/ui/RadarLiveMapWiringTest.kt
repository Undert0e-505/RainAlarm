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
        assertTrue(screen.contains("enabled = hasFreshLiveFix"))
        assertTrue(!topRow.contains("RadarLayerSegments"))
    }

    @Test fun liveMarkerUsesLightweightProjectionWithoutReplanningRasterMesh() {
        val map = source("RadarImageMap.kt")
        val gl = source("LegacyRadarGlOverlayView.kt")
        assertTrue(map.contains("else overlay.setMarker(markerPlace)"))
        assertTrue(map.contains("if (!followLive || teardown.isClosed) return@LaunchedEffect"))
        assertTrue(map.contains("REASON_API_GESTURE"))
        assertTrue(gl.contains("if (moved) updateMarkerProjection(mapPlace)"))
    }
}
