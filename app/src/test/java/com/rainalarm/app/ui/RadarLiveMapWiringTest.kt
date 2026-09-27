package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveMapWiringTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/rainalarm/app/ui/$name"),
        File("app/src/main/java/com/rainalarm/app/ui/$name"),
    ).first(File::isFile).readText()

    @Test fun travelControlIsAlwaysVisibleInTopRowAndHasAccessibleStates() {
        val screen = source("RadarScreen.kt")
        val topRow = screen.substringAfter(
            "Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically)",
        ).substringBefore("RadarLayerSegments(enabledMapLayers")
        assertTrue(!topRow.contains("if (selectedPlaceId == CURRENT_LOCATION_ID)"))
        assertTrue(screen.contains("IconButton(onClick = onTravelMode"))
        assertTrue(topRow.contains("IconButton(onClick = { onFollowLiveChange(!followLive) }"))
        assertTrue(screen.contains("if (enabled) requestTravelMode() else onFollowLiveChange(false)"))
        assertTrue(screen.contains("R.string.radar_follow_stop"))
        assertTrue(screen.contains("R.string.radar_follow_start"))
        assertTrue(screen.contains("R.string.radar_travel_mode"))
        assertTrue(screen.contains("travelNoticeActivationToken++"))
        assertTrue(screen.contains("delay(TravelActivationNoticePolicy.DURATION_MILLIS)"))
        assertTrue(screen.contains("if (!followLive) travelNoticeVisible = false"))
        assertTrue(!screen.contains("if (followLive) Text("))
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
        assertTrue(map.contains("if (activeEntryFocusToken != null) return@LaunchedEffect"))
        assertTrue(map.contains("RadarEntryFocusCompletionPolicy.finalTarget("))
        assertTrue(map.contains("latestFollowLive && mapPlace.isCurrentLocation"))
        assertTrue(map.contains("ready.cancelTransitions()"))
        assertTrue(map.contains("ready.easeCamera(CameraUpdateFactory.newCameraPosition(northUpCamera(target)), duration)"))
        assertTrue(map.contains("REASON_API_GESTURE"))
        assertTrue(gl.contains("if (moved) updateMarkerProjection(mapPlace)"))
    }

    @Test fun radarRefreshKeepsPlaybackButDirectScrubbingStillPauses() {
        val screen = source("RadarScreen.kt")
        assertTrue(screen.contains("val playbackRefreshIdentity = RadarPlaybackRefreshPolicy.identity("))
        assertTrue(screen.contains("session,\n        session?.providerSelection?.requested"))
        assertTrue(screen.contains("var playing by remember(playbackRefreshIdentity)"))
        assertTrue(screen.contains("onValueChange = {\n                chartTimeRequest?.let"))
        assertTrue(screen.contains("cursor = it.takeIf { value -> value.isFinite() }"))
        assertTrue(screen.contains("playing = false"))
        assertTrue(screen.contains("RadarEntryFocusActivationPolicy.shouldPrepare("))
        assertTrue(!screen.contains("!followLive && !(place.isCurrentLocation"))
    }
}
