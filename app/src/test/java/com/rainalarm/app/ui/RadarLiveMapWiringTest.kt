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
        assertTrue(map.contains("RadarTravelCameraFollower(Choreographer.getInstance())"))
        assertTrue(map.contains("travelCameraFollower.submit("))
        assertTrue(map.contains("travelCameraFollower.pauseForGesture()"))
        assertTrue(map.contains("travelCameraFollower.resumeAfterUnclaimedGesture("))
        assertTrue(map.contains("mapGestureOwnership.pointerFinished() && latestFollowLive"))
        assertTrue(map.contains("baseMarkerView.setFixedAtCenter(fixedTravelMarker)"))
        assertTrue(map.contains("activeRadarSlot?.setMarkerVisible(!fixedTravelMarker)"))
        val followCamera = map.substringAfter("// A live marker changes the snapshot's reference place")
        assertTrue(!followCamera.contains("ready.cancelTransitions()"))
        assertTrue(!followCamera.contains("ready.easeCamera("))
        assertTrue(map.contains("REASON_API_GESTURE"))
        assertTrue(map.contains("mapGestureOwnership.acceptsCameraStart"))
        val manualGesture = map.substringAfter("if (userGesture) {")
            .substringBefore("} else if (!travelCameraFollower.isRunning)")
        assertTrue(manualGesture.contains("ready.cancelTransitions()"))
        assertTrue(manualGesture.indexOf("travelCameraFollower.stop()") >
            manualGesture.indexOf("ready.cancelTransitions()"))
        assertTrue(manualGesture.contains("currentManualGesture()"))
        assertTrue(map.contains(
            "map, followLive, markerPlace.latitude, markerPlace.longitude, recenterSignal,",
        ))
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
        assertTrue(screen.contains("onValueChange = {"))
        assertTrue(screen.contains("chartTimeRequest?.let { request -> onChartTimeConsumed(request.token) }"))
        assertTrue(screen.contains("RadarTravelTimelinePolicy.dataCursorForManualSelection("))
        assertTrue(screen.contains("travelTimeAutomatic = false"))
        assertTrue(screen.contains("playing = false"))
        assertTrue(screen.contains("RadarEntryFocusActivationPolicy.shouldPrepare("))
        assertTrue(!screen.contains("!followLive && !(place.isCurrentLocation"))
    }

    @Test fun `AUTO play publishes its authoritative cursor before leaving AUTO`() {
        val screen = source("RadarScreen.kt")
        val timeline = screen.substringAfter("val timelineSliderValue =")
            .substringBefore("// Preserve the exact slider/tick x-domain")
        val play = timeline.substringAfter("IconButton(")
            .substringBefore("} else {\n                        tourPlaying")
        val handoff = play.indexOf("RadarPlaybackHandoffPolicy.startingCursorSeconds(")
        val leaveAuto = play.indexOf("travelTimeAutomatic = false")
        val startPlayback = play.indexOf("playing = !playing")

        assertTrue(play.contains("if (travelTimeAutomatic && travelAutoFrame != null)"))
        assertTrue(handoff >= 0)
        assertTrue(handoff < leaveAuto)
        assertTrue(leaveAuto < startPlayback)
        assertTrue(play.contains("autoFrame = travelAutoFrame"))
        assertTrue(play.contains("endOffsetSeconds = endOffset"))
    }

    @Test fun travelOwnsASecondCadenceCurrentTimeOverlayUntilManualTakeover() {
        val screen = source("RadarScreen.kt")
        assertTrue(screen.contains("var travelTimeAutomatic by remember { mutableStateOf(false) }"))
        assertTrue(screen.contains("LaunchedEffect(\n        followLive,\n        travelTimelineActivationToken,"))
        assertTrue(screen.contains("LaunchedEffect(travelTimeAutomatic)"))
        assertTrue(screen.contains("delay(1_000L)"))
        assertTrue(screen.contains("RadarTravelAutoPolicy.frame("))
        assertTrue(screen.contains("val automaticTimelineCursor = travelAutoFrame?.let"))
        assertTrue(screen.contains("val timelineDomainStart = timelineStartEpochSeconds"))
        assertTrue(screen.contains("val timelineDomainEnd = timelineDomainStart + endOffset"))
        assertTrue(screen.contains("RadarTimelineTicks.withSelected("))
        assertTrue(screen.contains("R.string.radar_travel_auto_badge"))
        assertTrue(screen.contains("R.string.radar_travel_auto_accessibility"))
        assertTrue(screen.contains("travelTimelineActivationToken++"))
        assertTrue(screen.contains("travelAutoFrame.localMinuteText"))
        assertTrue(screen.contains("RadarTravelTransitionReason.TIMELINE_MANUAL"))
    }

    @Test fun `AUTO is inline in the filled Slider without a standalone badge or geometry change`() {
        val screen = source("RadarScreen.kt")
        val timeline = screen.substringAfter("val timelineSliderValue =")
            .substringBefore("private fun FeatureTourRadarOverlay(")
        val controls = timeline.substringBefore("// Preserve the exact slider/tick x-domain")
        val ticks = timeline.substringAfter("// Preserve the exact slider/tick x-domain")
        val inline = screen.substringAfter("private fun RadarTimelineInlineAutoLabel(")
            .substringBefore("private fun FeatureTourRadarOverlay(")

        assertTrue(controls.contains("IconButton("))
        assertTrue(controls.contains("modifier = Modifier.size(48.dp)"))
        assertTrue(controls.contains("Spacer(Modifier.width(6.dp))"))
        assertTrue(controls.contains("Modifier.weight(1f).height(48.dp).clipToBounds()"))
        assertTrue(controls.contains("if (travelTimeAutomatic) {\n                    RadarTimelineInlineAutoLabel("))
        assertTrue(controls.contains("text = stringResource(R.string.radar_travel_auto_badge)"))
        assertTrue(controls.contains("timelineSliderValue / endOffset"))
        assertTrue(ticks.contains("padding(start = 54.dp)"))
        assertTrue(!ticks.contains("Modifier.width(48.dp).height(21.dp)"))
        assertTrue(inline.contains("color = Color.Black"))
        assertTrue(inline.contains("fontSize = 15.sp"))
        assertTrue(inline.contains("lineHeight = 19.5.sp"))
        assertTrue(inline.contains("fontWeight = FontWeight.Bold"))
        assertTrue(inline.contains("overflow = TextOverflow.Visible"))
        assertTrue(!inline.contains("overflow = TextOverflow.Clip"))
        assertTrue(inline.contains("RadarTimelineInlineAutoLabelPolicy.place("))
        assertTrue(inline.contains("gapPx = with(density) { 8.dp.toPx() }"))
        assertTrue(inline.contains("glyphSafetyInsetPx = with(density) { 2.dp.toPx() }"))
        assertTrue(inline.contains("label.place(it.leftPx.toInt(), it.topPx.toInt())"))
        assertTrue(!inline.contains(".background("))
        assertTrue(!inline.contains("RoundedCornerShape"))
        assertTrue(!inline.contains(".border("))
        assertTrue(screen.contains("RadarTravelAutoPolicy.unavailableFrame("))
    }

    @Test fun `play triangle grows another thirty percent without changing hit target or pause layout`() {
        val screen = source("RadarScreen.kt")
        val timeline = screen.substringAfter("val timelineSliderValue =")
            .substringBefore("// Preserve the exact slider/tick x-domain")
        val play = timeline.substringAfter("IconButton(")
            .substringBefore("Spacer(Modifier.width(6.dp))")

        assertTrue(play.contains("modifier = Modifier.size(48.dp)"))
        assertTrue(play.contains("if (displayPlaying) Icons.Default.Pause else Icons.Default.PlayArrow"))
        assertTrue(play.contains(
            "modifier = if (displayPlaying) Modifier else Modifier.size(37.44.dp)",
        ))
        assertTrue(!play.contains("Modifier.size(48.dp),\n                    contentDescription"))
    }

    @Test fun `selected timeline tick has equal length and collision visibility owns its line`() {
        val screen = source("RadarScreen.kt")
        val tickRow = screen.substringAfter("private fun RadarTimelineTickRow(")
            .substringBefore("internal fun Modifier.radarPageSwipeInput(")
        assertTrue(tickRow.contains("Spacer(Modifier.width(1.dp).height(4.dp)"))
        assertTrue(!tickRow.contains("if (tick.selected) 6.dp else 4.dp"))
        assertTrue(tickRow.contains("selected = ticks.map { it.selected }"))
        assertTrue(tickRow.contains("if (placement.markerVisible)"))
        assertTrue(tickRow.contains("if (placement.labelVisible)"))
        assertTrue(tickRow.contains("placement.labelCenterPx"))
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
