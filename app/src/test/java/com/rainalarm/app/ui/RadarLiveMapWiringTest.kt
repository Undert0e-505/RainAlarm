package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLiveMapWiringTest {
    private fun source(name: String): String = listOf(
        File("src/main/java/com/rainalarm/app/ui/$name"),
        File("app/src/main/java/com/rainalarm/app/ui/$name"),
    ).first(File::isFile).readText()

    private fun appSource(name: String): String = listOf(
        File("src/main/java/com/rainalarm/app/$name"),
        File("app/src/main/java/com/rainalarm/app/$name"),
    ).first(File::isFile).readText()

    @Test fun travelControlIsAlwaysVisibleInTopRowAndHasAccessibleStates() {
        val screen = source("RadarScreen.kt")
        val topRow = screen.substringAfter(
            "Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically)",
        ).substringBefore("RadarLayerSegments(")
        assertTrue(!topRow.contains("if (selectedPlaceId == CURRENT_LOCATION_ID)"))
        assertTrue(screen.contains("IconButton(onClick = onTravelMode"))
        assertTrue(topRow.contains("IconButton(onClick = { onFollowLiveChange(true) }"))
        assertTrue(screen.contains("if (enabled) requestTravelMode() else onFollowLiveChange(false)"))
        assertTrue(screen.contains("R.string.radar_follow_start"))
        assertTrue(screen.contains("R.string.radar_travel_mode"))
        assertTrue(screen.contains("travelNoticeActivationToken++"))
        assertTrue(screen.contains("delay(TravelActivationNoticePolicy.DURATION_MILLIS)"))
        assertTrue(screen.contains("if (!followLive) travelNoticeVisible = false"))
        assertTrue(!screen.contains("onDispose { onFollowLiveChange(false) }"))
        assertTrue(!screen.contains("if (followLive && !RadarLiveMapPolicy.canFollow"))
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
        assertTrue(map.contains("mapGestureOwnership.acceptsCameraStart"))
        assertTrue(map.contains("RadarTravelTransitionReason.PROGRAMMATIC_CAMERA"))
        assertTrue(gl.contains("if (moved) updateMarkerProjection(mapPlace)"))
    }

    @Test fun travelVisualProjectionCannotBecomeAWeatherOrRadarAcquisitionAnchor() {
        val activity = appSource("MainActivity.kt")
        val screen = source("RadarScreen.kt")
        val acceptFix = activity.substringAfter("private suspend fun acceptLiveFix(")
            .substringBefore("private fun publishTravelDisplayTarget")
        val displayPublisher = activity.substringAfter("private fun publishTravelDisplayTarget")
            .substringBefore("private fun applyTravelTransition")
        val displayedPlace = screen.substringAfter("val displayedMapPlace =")
            .substringBefore("val sessionReusable")

        assertTrue(activity.contains("MutableStateFlow<TravelDisplayTarget?>(null)"))
        assertTrue(acceptFix.contains("travelVisualMotionTracker.accept(fix"))
        assertTrue(!acceptFix.contains("publishTravelDisplayTarget("))
        assertTrue(acceptFix.contains("_livePlace.value = place"))
        assertTrue(displayPublisher.contains("_travelDisplayTarget.value = candidate"))
        assertTrue(!displayPublisher.contains("_livePlace.value"))
        assertTrue(!displayPublisher.contains("ForegroundLocationSnapshot.update"))
        assertTrue(!displayPublisher.contains("requestForegroundMonitoringRefresh"))
        assertTrue(displayedPlace.contains("val livePresentationPlace"))
        assertTrue(displayedPlace.contains("liveMapPlace.copy("))
        assertTrue(screen.contains("markerPlace = RadarLiveMapPolicy.marker(displayedMapPlace, livePresentationPlace)"))
        assertTrue(screen.contains("hasFreshLiveFix = RadarLiveMapPolicy.canFollow(selectedPlaceId, liveMapPlace)"))
    }

    @Test fun radarRefreshKeepsPlaybackButDirectScrubbingStillPauses() {
        val screen = source("RadarScreen.kt")
        assertTrue(screen.contains("val playbackRefreshIdentity = RadarPlaybackRefreshPolicy.identity("))
        assertTrue(screen.contains("session,\n        session?.providerSelection?.requested"))
        assertTrue(screen.contains("var playing by remember(playbackRefreshIdentity)"))
        assertTrue(screen.contains("onValueChange = {\n                chartTimeRequest?.let"))
        assertTrue(screen.contains("RadarTravelTimelinePolicy.dataCursorForManualSelection("))
        assertTrue(screen.contains("travelTimeAutomatic = false"))
        assertTrue(screen.contains("playing = false"))
        assertTrue(screen.contains("RadarEntryFocusActivationPolicy.shouldPrepare("))
        assertTrue(!screen.contains("!followLive && !(place.isCurrentLocation"))
    }

    @Test fun travelOwnsASecondCadenceRollingTimelineUntilManualTakeover() {
        val screen = source("RadarScreen.kt")
        assertTrue(screen.contains("var travelTimeAutomatic by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("LaunchedEffect(followLive, travelTimelineActivationToken)"))
        assertTrue(screen.contains("LaunchedEffect(travelTimeAutomatic)"))
        assertTrue(screen.contains("delay(1_000L)"))
        assertTrue(screen.contains("RadarTravelTimelinePolicy.frame("))
        assertTrue(screen.contains("travelTimelineFrame?.displayCursorSeconds"))
        assertTrue(screen.contains("R.string.radar_travel_auto_badge"))
        assertTrue(screen.contains("R.string.radar_travel_auto_accessibility"))
        assertTrue(screen.contains("val tickStart = travelTimelineFrame?.displayStartEpochSeconds"))
        assertTrue(screen.contains("travelTimelineActivationToken++"))
        assertTrue(screen.contains("RadarTravelClockPolicy.snapshotAt("))
        assertTrue(screen.contains("travelClock.localMinuteText"))
        assertTrue(screen.contains("RadarTravelTransitionReason.TIMELINE_MANUAL"))
    }

    @Test fun transientTravelNoticeCannotOwnOrRestartNativeMapComposition() {
        val screen = source("RadarScreen.kt")
        val player = screen.substringAfter("private fun ColumnScope.RadarPlayer(")
        val mapHost = player.substringAfter("Box(\n        modifier = Modifier.fillMaxWidth().weight(1f)")
            .substringBefore("Column(\n        Modifier.fillMaxWidth().featureTourTarget(")
        val statusStack = screen.substringAfter("private fun RadarOperationalStatusStack(")
            .substringBefore("private data class RadarNativeMapInputs(")

        assertTrue(mapHost.contains("key(\"radar-native-map\") { RadarNativeMap("))
        assertTrue(mapHost.indexOf("key(\"radar-native-map\")") <
            mapHost.indexOf("BoxWithConstraints(Modifier.fillMaxSize())"))
        assertTrue(screen.contains("temporaryLightningNoticePending: State<Boolean>"))
        assertTrue(statusStack.contains("LaunchedEffect(travelNoticeActivationToken)"))
        assertTrue(statusStack.contains("delay(TravelActivationNoticePolicy.DURATION_MILLIS)"))
        assertTrue(statusStack.contains("travelNoticeVisible = false"))
    }

    @Test fun healthyManualRefreshDoesNotRecreateTheNativeRenderer() {
        val screen = source("RadarScreen.kt")
        val outerRefresh = screen.substringAfter("val performManualRefresh: () -> Unit = {")
            .substringBefore("val setLayerEnabled:")
        val player = screen.substringAfter("private fun ColumnScope.RadarPlayer(")

        assertTrue(!outerRefresh.contains("rendererRecoveryGeneration++"))
        assertTrue(player.contains("RadarManualRefreshPolicy.shouldRecoverRenderer("))
        assertTrue(player.contains("onRendererRecovery()"))
        assertTrue(player.contains("IconButton(onClick = refreshMapAndData)"))
    }

    @Test fun travelReassertionClearsAnyTransientRadarTargetBeforeUsingCachedCurrentFix() {
        val activity = appSource("MainActivity.kt")
        val activation = activity.substringAfter("fun activateTravelMode(): Boolean {")
            .substringBefore("fun useCurrentLocation()")

        assertTrue(activation.contains("clearTransientRadarTarget()"))
        assertTrue(
            activation.indexOf("clearTransientRadarTarget()") <
                activation.indexOf("val currentReady"),
        )
    }
}
