package com.rainalarm.app.ui

import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.NowCardAppearance
import com.rainalarm.app.data.EumetLayerMetadata
import com.rainalarm.app.data.EumetProduct
import com.rainalarm.app.data.RadarMapLayer
import com.rainalarm.app.data.SatelliteCachedFrame
import com.rainalarm.app.data.SatelliteFrameAssetRequest
import java.io.File
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherUiWiringTest {
    private fun source(name: String) = listOf(
        File("src/main/java/com/rainalarm/app/ui/$name"),
        File("app/src/main/java/com/rainalarm/app/ui/$name"),
    ).first(File::isFile).readText()

    private fun cachedSatellite(product: EumetProduct, time: Long): SatelliteCachedFrame {
        val metadata = EumetLayerMetadata(
            product.conceptualLayer, time, -15.0, 47.2, 6.0, 63.3, product,
        )
        return SatelliteCachedFrame(
            SatelliteFrameAssetRequest(
                metadata.frameIdentity, "https://view.eumetsat.int/geoserver/wms",
                2, 2, metadata,
            ),
            File("${metadata.frameIdentity}.png"),
        )
    }

    @Test fun `deleted place cannot be reinserted by pending UI drag order`() {
        val york = SavedPlace("York", 53.96, -1.08)
        val bath = SavedPlace("Bath", 51.38, -2.36)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        collection = PlaceCollectionRules.upsert(collection, bath, select = false)
        val pending = listOf(bath.id, york.id, collection.places.first().id)
        assertEquals(pending, DisplayedPlaceRowsPolicy.reconcile(collection, pending).places.map { it.id })
        collection = PlaceCollectionRules.delete(collection, york.id)
        val afterDelete = DisplayedPlaceRowsPolicy.reconcile(collection, pending)
        assertEquals(collection.places, afterDelete.places)
        assertNull(afterDelete.pendingOrder)
        val single = PlaceCollectionRules.upsert(
            PlaceCollection(places = emptyList(), selectedId = "current-location"), york)
        val empty = PlaceCollectionRules.delete(single, york.id)
        val afterLastDelete = DisplayedPlaceRowsPolicy.reconcile(empty, listOf(york.id))
        assertTrue(afterLastDelete.places.isEmpty())
        assertNull(afterLastDelete.pendingOrder)
        val placesUi = source("PlacesScreen.kt")
        assertTrue(placesUi.contains("displayedPlaces.removeAll { it.id == place.id }"))
        assertTrue(placesUi.contains("deletePlace(place.id) { succeeded ->"))
        assertTrue(placesUi.contains("IconButton(onClick = onDelete)"))
        assertTrue(placesUi.contains("detectDragGesturesAfterLongPress("))
        val main = listOf(File("src/main/java/com/rainalarm/app/MainActivity.kt"),
            File("app/src/main/java/com/rainalarm/app/MainActivity.kt")).first(File::isFile).readText()
        assertTrue(main.contains("places.delete(id)\n                alertPreferences.clearDeletedSavedPlace(id)"))
    }

    @Test fun `optimistic multiple deletes reconcile success and failed write independently`() {
        val york = SavedPlace("York", 53.96, -1.08)
        val bath = SavedPlace("Bath", 51.38, -2.36)
        var collection = PlaceCollectionRules.upsert(PlaceCollection(), york)
        collection = PlaceCollectionRules.upsert(collection, bath, select = false)
        val pending = setOf(york.id, bath.id)
        val optimistic = DisplayedPlaceRowsPolicy.reconcile(collection, null, pending)
        assertEquals(listOf("london-default"), optimistic.places.map { it.id })
        assertEquals(pending, optimistic.pendingDeletes)
        collection = PlaceCollectionRules.delete(collection, york.id)
        val oneConfirmed = DisplayedPlaceRowsPolicy.reconcile(collection, null, pending)
        assertEquals(listOf("london-default"), oneConfirmed.places.map { it.id })
        assertEquals(setOf(bath.id), oneConfirmed.pendingDeletes)
        val failureRestored = DisplayedPlaceRowsPolicy.reconcile(collection, null, emptySet())
        assertEquals(listOf("london-default", bath.id), failureRestored.places.map { it.id })
        val placesUi = source("PlacesScreen.kt")
        assertTrue(placesUi.contains("pendingDeletes = pendingDeletes - place.id"))
        assertTrue(placesUi.contains("Could not delete"))
    }

    @Test fun `notification switch lives only in settings and provider rows are titles only`() {
        val settings = source("SettingsScreen.kt")
        val places = source("PlacesScreen.kt")
        assertTrue(settings.contains("Text(\"Rain Notification\""))
        assertTrue(settings.contains("notificationPermission.launch"))
        assertFalse(places.contains("Rain approaching alert"))
        assertFalse(places.contains("alertSnapshot"))
        assertFalse(settings.contains("badge = \"DEFAULT\""))
    }

    @Test fun `provider taps settle before expensive Now reload and cancellation stays non-error`() {
        val main = source("../MainActivity.kt")
        val forecast = source("../data/RadarForecastRepository.kt")
        val map = source("RadarImageMap.kt")
        assertTrue(main.contains("private val pendingRadarProvider = MutableStateFlow<RadarProviderKind?>(null)"))
        assertTrue(main.contains("delay(RadarProviderSwitchPolicy.settleMillis)"))
        assertTrue(main.contains("providerPersistenceJob?.cancel()"))
        assertTrue(main.contains("forecastLoadJob?.cancel()"))
        assertTrue(main.contains("combine(selectedPlace, settledRadarProvider, showLikelySnow"))
        assertTrue(main.contains("persisted.takeIf { pending == null }"))
        assertTrue(main.contains("catch (cancelled: CancellationException) {\n                throw cancelled"))
        assertTrue(main.contains("if (generation != forecastLoadGeneration || pendingRadarProvider.value != null ||"))
        assertTrue(main.contains("pendingRadarProvider.value != null"))
        assertTrue(main.contains("if (!changesPersisted) forecastRefreshVersion.value++"))
        assertFalse(main.contains("runCatching { radarSettings.setProvider(provider) }"))
        assertTrue(forecast.contains("finally {\n            session?.release()"))
        assertTrue(map.contains("releaseSession = session::release"))
    }

    @Test fun `places routes postcode search and exposes only a resolved live snapshot save`() {
        val places = source("PlacesScreen.kt")
        val settings = source("SettingsScreen.kt")
        assertTrue(places.contains("val geocoder = remember { PlaceGeocoder() }"))
        assertTrue(places.contains("Towns: Open-Meteo · UK postcodes: postcodes.io"))
        assertTrue(places.contains("val liveSnapshot = if (collection.selectedId == CURRENT_LOCATION_ID)"))
        assertTrue(places.contains("LiveLocationSavePolicy.snapshot((locationState as? LocationUiState.Active)?.place)"))
        assertTrue(places.contains("onClick = { saveAndSelect(liveSnapshot) }"))
        assertTrue(places.contains("Text(\"Save current location\")"))
        assertTrue(settings.contains("UK postcode lookup: postcodes.io"))
    }

    @Test fun `radar refresh reloads session and layer without replacing camera memory`() {
        val radar = source("RadarScreen.kt")
        assertTrue(radar.contains("onRefresh = { reload++; layerRefresh++; refreshPointWeather() }"))
        assertTrue(radar.contains("cameraMemory = cameraMemory"))
        assertTrue(radar.contains("LaunchedEffect(lightningEnabled"))
        assertTrue(radar.contains("cloudsEnabled, place?.id"))
        assertTrue(radar.contains("LaunchedEffect(windEnabled"))
        assertTrue(radar.contains("if (!lightningEnabled)"))
        assertTrue(radar.contains("if (!cloudsEnabled)"))
        assertTrue(radar.contains("if (!windEnabled)"))
        assertTrue(radar.contains("onWindViewportChanged = { windViewport = it }"))
        val map = source("RadarImageMap.kt")
        assertTrue(map.contains("ImageSource(sourceId, quad, bitmap)"))
        assertFalse(map.contains("ImageSource(sourceId, quad, URI(request.url))"))
        assertFalse(map.contains("TileSet(\"2.2.0\", metadata.tileUrl())"))
        assertFalse(map.contains("SatelliteLayerRenderPolicy.choiceForSource(sourceId)"))
        assertTrue(map.contains("addOnCameraIdleListener"))
        val idleBody = map.substringAfter("val idle = MapLibreMap.OnCameraIdleListener {")
            .substringBefore("cameraIdleListener = idle")
        assertTrue(idleBody.contains("overlay.onCameraMoved()"))
        assertTrue(idleBody.contains("staticFallback?.onCameraMoved()"))
        val renderer = source("LegacyRadarGlOverlayView.kt")
        assertTrue(renderer.contains("val selectedMesh = if (selectedTier != activeTier)"))
        val publishedProjection = renderer.substringAfter("synchronized(stateLock) {\n            // Tier and its projected vertices")
            .substringBefore("requestRender()")
        assertTrue(publishedProjection.contains("activeTier = selectedTier"))
        assertTrue(publishedProjection.contains("geoMesh = selectedMesh"))
        assertTrue(publishedProjection.contains("screenVertices = updated"))
        assertFalse(map.contains("canvas.drawText(\"${'$'}{speed.roundToInt()}\""))
        assertTrue(map.contains("if (oldPlace?.id == mapPlace.id && !cameraMemory.hasPendingRecenter(recenterSignal))"))
        assertTrue(map.contains("oldPlace?.let { saveCamera(ready, it) }"))
        assertTrue(map.contains("val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)"))
        assertTrue(radar.contains("saveAndSelect(SavedPlace(name.trim(), point.latitude, point.longitude))"))
    }

    @Test fun `Now and alerts share dense first open radar minute series builder`() {
        val forecast = listOf(
            File("src/main/java/com/rainalarm/app/data/RadarForecastRepository.kt"),
            File("app/src/main/java/com/rainalarm/app/data/RadarForecastRepository.kt"),
        ).first(File::isFile).readText()
        val alerts = listOf(
            File("src/main/java/com/rainalarm/app/alerts/RainAlerts.kt"),
            File("app/src/main/java/com/rainalarm/app/alerts/RainAlerts.kt"),
        ).first(File::isFile).readText()
        assertTrue(forecast.contains("fun buildOpenRadarMinuteSeries"))
        assertTrue(forecast.contains("field = session.velocity(samplingTier)?.futureField"))
        assertTrue(forecast.contains("aggregateMotion = session.motion"))
        assertTrue(forecast.contains("coverageGrid = session.latestDetailCoverage"))
        assertTrue(forecast.contains("buildOpenRadarMinuteSeries(session, evaluatedAt.epochSecond)"))
        assertTrue(forecast.contains("radarSettings.selectedProvider() == RadarProviderKind.OPEN_RAINVIEWER"))
        assertTrue(forecast.contains("Open radar could not provide a reliable local analysis"))
        assertTrue(alerts.contains("buildOpenRadarMinuteSeries(session, evaluatedAt.epochSecond)"))
        assertFalse(alerts.contains("OpenMinuteSeriesBuilder.fromDenseField("))
    }

    @Test fun `radar header centers selected place through existing camera tick`() {
        val radar = source("RadarScreen.kt")
        val main = listOf(File("src/main/java/com/rainalarm/app/MainActivity.kt"),
            File("app/src/main/java/com/rainalarm/app/MainActivity.kt"))
            .first(File::isFile).readText()
        assertTrue(radar.contains("IconButton(onClick = recenterToSelectedPlace, enabled = canCenterSelected"))
        assertEquals(48, NowHeaderLayoutPolicy.switchDp)
        assertEquals(8, NowHeaderLayoutPolicy.gapDp)
        assertEquals(0f, NowHeaderLayoutPolicy.titleMaxWidthDp(100f), 0f)
        assertEquals(216f, NowHeaderLayoutPolicy.titleMaxWidthDp(328f), 0f)
        assertEquals(648f, NowHeaderLayoutPolicy.titleMaxWidthDp(760f), 0f)
        assertTrue(radar.contains("BoxWithConstraints(Modifier.fillMaxWidth().height(NowHeaderLayoutPolicy.heightDp.dp)"))
        assertTrue(radar.contains("val maxTitleWidth = NowHeaderLayoutPolicy.titleMaxWidthDp(maxWidth.value).dp"))
        assertTrue(radar.contains("Modifier.widthIn(max = maxTitleWidth)"))
        assertFalse(radar.contains("modifier = Modifier.weight(1f))\n            PlaceSwitcher"))
        assertTrue(radar.contains("textAlign = TextAlign.Center"))
        assertTrue(radar.contains("PlaceSwitcher(places, locationState, selectPlace"))
        assertTrue(main.contains("recenterToSelectedPlace = viewModel::recenterToSelectedPlace"))
        assertTrue(main.contains("_currentRecenterTick.value++"))
    }

    @Test fun `Now and Radar share title geometry typography and top inset`() {
        val now = source("NowScreen.kt")
        val radar = source("RadarScreen.kt")
        for ((width, height, scale) in listOf(
            Triple(320, 580, 1f), Triple(390, 700, 1f), Triple(390, 700, 1.4f),
        )) {
            val nowMetrics = NowLayoutPolicy.measure(width, height, scale)
            assertEquals(NowHeaderLayoutPolicy.horizontalPaddingDp(width, height, scale),
                nowMetrics.horizontalPaddingDp)
            assertEquals(NowHeaderLayoutPolicy.verticalPaddingDp, nowMetrics.verticalPaddingDp)
            assertEquals(NowHeaderLayoutPolicy.heightDp, nowMetrics.headerHeightDp)
            assertEquals(NowHeaderLayoutPolicy.simplifyText(width, height, scale), nowMetrics.simplifyText)
            assertEquals(if (nowMetrics.simplifyText) 22 else 26,
                NowHeaderLayoutPolicy.titleFontSizeSp(nowMetrics.simplifyText))
            assertEquals(if (nowMetrics.simplifyText) 26 else 30,
                NowHeaderLayoutPolicy.titleLineHeightSp(nowMetrics.simplifyText))
        }
        assertTrue(now.contains("fontSize = NowHeaderLayoutPolicy.titleFontSizeSp(metrics.simplifyText).sp"))
        assertTrue(radar.contains("fontSize = NowHeaderLayoutPolicy.titleFontSizeSp(compactTitle).sp"))
        assertTrue(now.contains("lineHeight = NowHeaderLayoutPolicy.titleLineHeightSp(metrics.simplifyText).sp"))
        assertTrue(radar.contains("lineHeight = NowHeaderLayoutPolicy.titleLineHeightSp(compactTitle).sp"))
        assertTrue(radar.contains("horizontal = NowHeaderLayoutPolicy.horizontalPaddingDp"))
        assertTrue(radar.contains("vertical = NowHeaderLayoutPolicy.verticalPaddingDp.dp"))
        assertTrue(radar.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue(radar.contains("Modifier.widthIn(max = maxTitleWidth).semantics { heading() }"))
    }

    @Test fun `compass cardinals double in size without changing dial anchors or rings`() {
        val now = source("NowScreen.kt")
        for (letter in listOf("N", "E", "S", "W")) {
            assertTrue(now.contains("Text(\"$letter\", color = " +
                (if (letter == "N") "NowText" else "NowMuted") +
                ", fontSize = 24.sp, lineHeight = 24.sp"))
        }
        assertTrue(now.contains("fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter)"))
        assertTrue(now.contains("modifier = Modifier.align(Alignment.CenterEnd).padding(end = NowCompassCardinalPolicy.eastEndInsetDp.dp)"))
        assertTrue(now.contains("modifier = Modifier.align(Alignment.BottomCenter)"))
        assertTrue(now.contains("modifier = Modifier.align(Alignment.CenterStart).padding(start = 3.dp)"))
        assertTrue(now.contains("val outer = size.minDimension * 0.43f"))
        assertTrue(now.contains("val inner = size.minDimension * 0.30f"))
        assertTrue(now.contains("NowWeatherReadouts(weather, visibleWeatherMetrics,"))
    }

    @Test fun `both place switchers use one outlined open-arrow vector`() {
        val switcher = source("PlaceSwitcher.kt")
        val now = source("NowScreen.kt")
        val radar = source("RadarScreen.kt")
        val vector = listOf(File("src/main/res/drawable/ic_place_switch.xml"),
            File("app/src/main/res/drawable/ic_place_switch.xml"))
            .first(File::isFile).readText()
        assertTrue(switcher.contains("painterResource(R.drawable.ic_place_switch)"))
        assertTrue(switcher.contains("modifier = Modifier.size(48.dp)"))
        assertTrue(switcher.contains("contentDescription = \"Switch place, selected"))
        assertTrue(now.contains("PlaceSwitcher(places, locationState, selectPlace, useCurrentLocation)"))
        assertTrue(radar.contains("PlaceSwitcher(places, locationState, selectPlace"))
        assertFalse(now.contains("SwapHoriz"))
        assertFalse(switcher.contains("SwapHoriz"))
        assertEquals(1, Regex("<path\\b").findAll(vector).count())
        assertTrue(vector.contains("android:fillColor=\"#00000000\""))
        assertTrue(vector.contains("android:strokeLineCap=\"round\""))
        assertTrue(vector.contains("android:strokeLineJoin=\"round\""))
        assertTrue(vector.contains("android:pathData=\"M13.4,7"))
        assertTrue(now.contains("Spacer(Modifier.width(NowHeaderLayoutPolicy.sideReserveDp.dp))"))
        assertTrue(now.contains("modifier = Modifier.widthIn(max = maxTitleWidth).semantics { heading() }"))
        assertTrue(now.contains("Spacer(Modifier.width(NowHeaderLayoutPolicy.gapDp.dp))"))
        assertTrue(now.contains("contentAlignment = Alignment.Center"))
    }

    @Test fun `layer segments use a second row and restore forecast label width`() {
        assertEquals(12, RadarTopControlsPolicy.timeTopDp)
        assertEquals(18, RadarTopControlsPolicy.timeLabelSp(328))
        assertEquals(20, RadarTopControlsPolicy.timeLabelSp(400))
        assertEquals(48, RadarTopControlsPolicy.segmentSizeDp)
        assertEquals(144, RadarTopControlsPolicy.segmentGroupWidthDp)
        assertEquals(RadarTopControlsPolicy.controlSizeDp * 3,
            RadarTopControlsPolicy.segmentGroupWidthDp)
        assertEquals(1, RadarTopControlsPolicy.segmentDividerDp)
        assertEquals(160, RadarTopControlsPolicy.timeLabelMaxWidthDp(328, true))
        assertEquals(208, RadarTopControlsPolicy.timeLabelMaxWidthDp(328, false))
        assertEquals(56, RadarTopControlsPolicy.segmentTopDp)
        assertEquals(108, RadarTopControlsPolicy.statusTopDp)
        assertEquals(4, RadarTopControlsPolicy.statusEndDp)
        assertEquals(220, RadarTopControlsPolicy.statusMaxWidthDp(400))
        val radar = source("RadarScreen.kt")
        assertFalse(radar.contains("top = if (mapLayer == RadarMapLayer.WIND) 56.dp"))
        assertTrue(radar.contains("top = RadarTopControlsPolicy.timeTopDp.dp"))
        assertTrue(radar.contains(".widthIn(max = RadarTopControlsPolicy.timeLabelMaxWidthDp("))
        assertTrue(radar.contains("enabledMapLayers, ancillaryStatuses, satellitePreparation,"))
        assertTrue(radar.contains("Modifier.align(Alignment.TopEnd).padding(\n" +
            "                    top = RadarTopControlsPolicy.segmentTopDp.dp"))
        assertTrue(radar.contains("end = RadarTopControlsPolicy.controlsEndDp.dp"))
        assertTrue(radar.contains("top = RadarTopControlsPolicy.statusTopDp.dp"))
        assertTrue(radar.contains("end = RadarTopControlsPolicy.statusEndDp.dp"))
    }

    @Test fun `radar keeps only native attribution and puts provider credits in About data`() {
        val radar = source("RadarScreen.kt")
        val map = source("RadarImageMap.kt")
        val settings = source("SettingsScreen.kt")
        assertFalse(radar.contains("Radar: MeteoGroup/DTN | Map:"))
        assertFalse(radar.contains("Radar: RainViewer | Map:"))
        assertTrue(map.contains(".logoEnabled(false)"))
        assertTrue(map.contains(".attributionEnabled(true)"))
        assertTrue(map.contains(".attributionGravity(Gravity.BOTTOM or Gravity.START)"))
        assertTrue(map.contains("ready.uiSettings.isAttributionEnabled = true"))
        assertTrue(settings.contains("Regional radar: MeteoGroup/DTN."))
        assertTrue(settings.contains("Open radar: RainViewer."))
        assertTrue(settings.contains("Open-Meteo (CC BY 4.0)"))
        assertTrue(settings.contains("© EUMETSAT (CC BY 4.0)"))
        assertTrue(settings.contains("© OpenStreetMap contributors"))
    }

    @Test fun `top controls exclude second row segments and statuses remain right aligned`() {
        val radar = source("RadarScreen.kt")
        val controls = radar.substringAfter(
            "Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically)",
        ).substringBefore("RadarLayerSegments(enabledMapLayers")
        val follow = controls.indexOf("Stop following live location")
        val refresh = controls.indexOf("Refresh radar and map layer")
        val centre = controls.indexOf("Use current device location")
        assertTrue(follow >= 0)
        assertTrue(follow < refresh)
        assertTrue(refresh < centre)
        assertFalse(controls.contains("RadarLayerSegments"))
        assertTrue(controls.contains("tint = if (followLive) Accent.copy(alpha = 0.85f)"))
        assertTrue(controls.contains("else if (darkMap) Color.White else Color.Black"))
        assertFalse(controls.contains(".background(if (followLive)"))
        val segments = radar.substringAfter("private fun RadarLayerSegments(")
            .substringBefore("private sealed interface AncillaryStatus")
        assertTrue(segments.contains("RadarMapLayer.overlays.forEach { layer ->"))
        assertTrue(segments.contains("RadarMapLayer.WIND -> Icons.Default.Air"))
        assertTrue(segments.contains("RadarMapLayer.LIGHTNING -> Icons.Default.Bolt"))
        assertTrue(segments.contains("RadarMapLayer.FOG -> Icons.Default.CloudQueue"))
        assertTrue(segments.contains(".background(if (active) Accent.copy(alpha = 0.18f) else Color.Transparent)"))
        assertTrue(segments.contains("tint = if (active) Accent else inactiveTint"))
        assertTrue(segments.contains("modifier = Modifier.size(24.dp)"))
        assertTrue(segments.contains("Canvas(Modifier.fillMaxSize())"))
        assertTrue(segments.contains("for (join in 1 until RadarMapLayer.overlays.size)"))
        assertTrue(segments.contains("drawLine(inactiveTint.copy(alpha = 0.3f)"))
        assertFalse(segments.contains("Spacer(Modifier.width(RadarTopControlsPolicy.segmentDividerDp.dp)"))
        assertTrue(segments.contains("role = Role.Switch"))
        assertFalse(radar.contains("DropdownMenu("))
        assertFalse(radar.contains("Map layers, "))
        assertTrue(radar.contains("\"${'$'}label loading\""))
        assertTrue(radar.contains("\"${'$'}label available\""))
        assertTrue(radar.contains("\"${'$'}label preparing\""))
        assertTrue(radar.contains("\"${'$'}label unavailable\""))
        assertEquals(1, Regex("ancillaryStatus is AncillaryStatus\\.Unavailable -> ancillaryStatus\\.message")
            .findAll(radar).count())
        assertFalse(radar.contains("\"Night only · fog / low cloud\""))
        assertFalse(radar.contains("\"Daylight status unavailable\""))
        assertTrue(radar.contains("contentDescription = \"${'$'}{layer.label} map layer\""))
        assertFalse(radar.contains("Wind · Open-Meteo model"))
        assertFalse(radar.contains("m ago"))
    }

    @Test fun `satellite feedback waits for terminal result then fades once per activation`() {
        var state = SatelliteFeedbackState()
        state = SatelliteFeedbackPolicy.onInput(state, enabled = true, terminal = false)
        assertTrue(state.enabled)
        assertTrue(state.visible)
        assertFalse(state.holdingTerminal)

        val stillLoading = SatelliteFeedbackPolicy.onInput(state, enabled = true, terminal = false)
        assertEquals(state, stillLoading)
        val terminal = SatelliteFeedbackPolicy.onInput(stillLoading, enabled = true, terminal = true)
        assertTrue(terminal.visible)
        assertTrue(terminal.holdingTerminal)
        assertEquals(1_000L, SatelliteFeedbackPolicy.terminalHoldMillis)
        assertTrue(SatelliteFeedbackPolicy.afterTerminalElapsed(
            terminal, terminal.generation, 999L).visible)
        val faded = SatelliteFeedbackPolicy.afterTerminalElapsed(
            terminal, terminal.generation, 1_000L)
        assertFalse(faded.visible)

        // Later loading/freshness changes during the same activation cannot reopen feedback.
        val periodicLoading = SatelliteFeedbackPolicy.onInput(faded, enabled = true, terminal = false)
        val periodicTerminal = SatelliteFeedbackPolicy.onInput(periodicLoading, enabled = true, terminal = true)
        assertFalse(periodicLoading.visible)
        assertFalse(periodicTerminal.visible)
    }

    @Test fun `satellite feedback timers are independent and disable cancels immediately`() {
        var lightning = SatelliteFeedbackPolicy.onInput(
            SatelliteFeedbackState(), enabled = true, terminal = false)
        var fog = SatelliteFeedbackPolicy.onInput(
            SatelliteFeedbackState(), enabled = true, terminal = false)
        lightning = SatelliteFeedbackPolicy.onInput(lightning, enabled = true, terminal = true)
        assertTrue(lightning.holdingTerminal)
        assertTrue(fog.visible)
        assertFalse(fog.holdingTerminal)
        lightning = SatelliteFeedbackPolicy.afterTerminalElapsed(
            lightning, lightning.generation, SatelliteFeedbackPolicy.terminalHoldMillis)
        assertFalse(lightning.visible)
        assertTrue(fog.visible)

        val disabled = SatelliteFeedbackPolicy.onInput(fog, enabled = false, terminal = false)
        assertFalse(disabled.enabled)
        assertFalse(disabled.visible)
        assertFalse(disabled.holdingTerminal)
        val reenabled = SatelliteFeedbackPolicy.onInput(disabled, enabled = true, terminal = false)
        assertTrue(reenabled.visible)

        val radar = source("RadarScreen.kt")
        val statuses = radar.substringAfter("private fun RadarLayerStatuses(")
            .substringBefore("private fun SatelliteActivationStatus(")
        assertTrue(statuses.contains("if (RadarMapLayer.WIND in enabledMapLayers)"))
        assertTrue(statuses.contains("RadarLayerStatus(RadarMapLayer.WIND"))
        assertTrue(statuses.contains("SatelliteActivationStatus(RadarMapLayer.LIGHTNING"))
        assertTrue(statuses.contains("SatelliteActivationStatus(RadarMapLayer.FOG"))
        assertTrue(radar.contains("delay(SatelliteFeedbackPolicy.terminalHoldMillis)"))
        assertTrue(radar.contains("exit = fadeOut(tween(SatelliteFeedbackPolicy.fadeMillis))"))
        assertTrue(radar.contains("if (enabled) AnimatedVisibility("))
    }

    @Test fun `full-set preparation labels are concise and layer specific`() {
        assertEquals("Preparing Clouds 3/6", SatellitePreparationLabelPolicy.label(
            RadarMapLayer.FOG, SatellitePreparationStatus.Preparing(3, 6),
        ))
        assertEquals("Preparing Lightning 8/12", SatellitePreparationLabelPolicy.label(
            RadarMapLayer.LIGHTNING, SatellitePreparationStatus.Preparing(8, 12),
        ))
        assertEquals("Clouds preparation failed · refresh", SatellitePreparationLabelPolicy.label(
            RadarMapLayer.FOG,
            SatellitePreparationStatus.Failed("Clouds preparation failed · refresh"),
        ))
        assertEquals("Clouds ready", SatellitePreparationLabelPolicy.label(
            RadarMapLayer.FOG, SatellitePreparationStatus.Ready,
        ))
        assertEquals("Rendering Clouds…", SatellitePreparationLabelPolicy.label(
            RadarMapLayer.FOG, SatellitePreparationStatus.Rendering,
        ))
    }

    @Test fun `satellite status cannot claim available before verified files are ready`() {
        val metadata = EumetLayerMetadata(
            RadarMapLayer.FOG, 1_000L, -15.0, 47.2, 6.0, 63.3,
            EumetProduct.FOG_LOW_CLOUD,
        )
        val availableMetadata = AncillaryStatus.Satellite(metadata)
        assertEquals("Preparing Clouds 0/5", SatelliteStatusLabelPolicy.label(
            RadarMapLayer.FOG, availableMetadata, SatellitePreparationStatus.Preparing(0, 5),
        ))
        assertEquals("Clouds preparing", SatelliteStatusLabelPolicy.label(
            RadarMapLayer.FOG, availableMetadata, null,
        ))
        assertEquals("Clouds rendering", SatelliteStatusLabelPolicy.label(
            RadarMapLayer.FOG, availableMetadata, SatellitePreparationStatus.Rendering,
        ))
        assertEquals("Clouds preparation failed · refresh", SatelliteStatusLabelPolicy.label(
            RadarMapLayer.FOG, availableMetadata,
            SatellitePreparationStatus.Failed("Clouds preparation failed · refresh"),
        ))
        assertEquals("Clouds available", SatelliteStatusLabelPolicy.label(
            RadarMapLayer.FOG, availableMetadata, SatellitePreparationStatus.Ready,
        ))
    }

    @Test fun `regional image failure retries without removing confirmed active source`() {
        val map = source("RadarImageMap.kt")
        val cache = listOf(
            File("src/main/java/com/rainalarm/app/data/SatelliteFrameCache.kt"),
            File("app/src/main/java/com/rainalarm/app/data/SatelliteFrameCache.kt"),
        ).first(File::isFile).readText()
        val buffers = map.substringAfter("private class SatelliteLayerBuffers(")
            .substringBefore("/** Twenty-five distinct requested map positions")
        assertFalse(map.contains("MapView.OnTileActionListener"))
        assertTrue(buffers.contains("onFrameInvalidated(choice, asset)"))
        assertTrue(map.contains("satelliteFrameStore.invalidate(frame.request)"))
        assertTrue(map.contains("satelliteRecoveryGeneration = satelliteRecoveryGeneration +"))
        assertTrue(cache.contains("const val maximumAttempts = 3"))
        val decodeFailure = buffers.substringAfter("bitmap decode failed")
            .substringBefore("private fun add(")
        assertFalse(decodeFailure.contains("state.active = null"))
    }

    @Test fun `clouds have no daylight gate and concurrent satellite ordering is stable`() {
        val selected = setOf(RadarMapLayer.WIND, RadarMapLayer.LIGHTNING, RadarMapLayer.FOG)
        assertTrue(RadarMapLayer.FOG in selected)
        assertNull(RadarLayerGatePolicy.unavailableReason(hasPlace = true))
        assertEquals("Select a place for this layer",
            RadarLayerGatePolicy.unavailableReason(hasPlace = false))

        val lightning = EumetLayerMetadata(RadarMapLayer.LIGHTNING, 2L, -20.0, 30.0, 20.0, 70.0)
        val clouds = EumetLayerMetadata(
            RadarMapLayer.FOG, 1L, -20.0, 30.0, 20.0, 70.0, EumetProduct.CLOUD_TYPE,
        )
        assertEquals(listOf(RadarMapLayer.FOG, RadarMapLayer.LIGHTNING),
            SatelliteLayerRenderPolicy.renderOrder)
        assertEquals(listOf(clouds, lightning),
            SatelliteLayerRenderPolicy.ordered(listOf(lightning, clouds)))
        assertTrue(SatelliteLayerRenderPolicy.sourceId(RadarMapLayer.FOG) !=
            SatelliteLayerRenderPolicy.sourceId(RadarMapLayer.LIGHTNING))
        assertTrue(SatelliteLayerRenderPolicy.layerId(RadarMapLayer.FOG) !=
            SatelliteLayerRenderPolicy.layerId(RadarMapLayer.LIGHTNING))
        assertTrue(SatelliteLayerRenderPolicy.sourceId(RadarMapLayer.FOG, 1L) !=
            SatelliteLayerRenderPolicy.sourceId(RadarMapLayer.FOG, 2L))

        val radar = source("RadarScreen.kt")
        assertTrue(radar.contains("CloudProductSelectionPolicy.preferred(currentWeather, it, layerNow)"))
        assertTrue(radar.contains("EumetViewRepository.cloudProducts("))
        assertTrue(radar.contains("if (retained == null) cloudsStatus = AncillaryStatus.Loading"))
        assertTrue(radar.contains("retained ?: AncillaryStatus.Unavailable(\"Clouds unavailable\")"))
    }

    @Test fun `slow satellite loads advance progressively at all playback speeds without stale scrubs`() {
        fun cloud(time: Long, product: EumetProduct = EumetProduct.CLOUD_TYPE) =
            EumetLayerMetadata(RadarMapLayer.FOG, time, -20.0, 30.0, 20.0, 70.0, product)
        val active = cloud(600L)
        val slowPending = cloud(1_200L)

        // While the same slow pending request loads, 1x/2x/4x playback can move the conflated
        // latest target several cadence frames ahead. The useful pending intermediate promotes.
        listOf(1_800L, 2_400L, 3_600L).forEach { latestTarget ->
            assertEquals(SatellitePendingAction.PROMOTE,
                SatelliteProgressiveLoadPolicy.whenReady(
                    active, slowPending, cloud(latestTarget), isPlaying = true))
        }
        assertEquals(SatellitePendingAction.PROMOTE,
            SatelliteProgressiveLoadPolicy.whenReady(
                active, slowPending, slowPending, isPlaying = false))
        assertEquals(SatellitePendingAction.DISCARD,
            SatelliteProgressiveLoadPolicy.whenReady(
                active, slowPending, cloud(1_800L), isPlaying = false))
        // Loop wrap, backward scrub and an intermediate beyond the latest target never snap back.
        assertEquals(SatellitePendingAction.DISCARD,
            SatelliteProgressiveLoadPolicy.whenReady(
                cloud(3_600L), cloud(4_200L), cloud(600L), isPlaying = true))
        assertEquals(SatellitePendingAction.DISCARD,
            SatelliteProgressiveLoadPolicy.whenReady(
                cloud(3_600L), cloud(4_200L), cloud(3_000L), isPlaying = false))
        assertEquals(SatellitePendingAction.DISCARD,
            SatelliteProgressiveLoadPolicy.whenReady(
                active, cloud(2_400L), cloud(1_800L), isPlaying = true))
        // A stale old day/night product cannot promote, but a forward intermediate already using
        // the newly desired product can.
        assertEquals(SatellitePendingAction.DISCARD,
            SatelliteProgressiveLoadPolicy.whenReady(active,
                cloud(1_200L, EumetProduct.FOG_LOW_CLOUD), cloud(1_800L), isPlaying = true))
        assertEquals(SatellitePendingAction.PROMOTE,
            SatelliteProgressiveLoadPolicy.whenReady(
                cloud(600L, EumetProduct.FOG_LOW_CLOUD), cloud(1_200L), cloud(1_800L), true))
    }

    @Test fun `satellite overlays follow radar time with independent bounded disk-backed warming`() {

        val radar = source("RadarScreen.kt")
        val map = source("RadarImageMap.kt")
        assertTrue(radar.contains("satelliteDisplayEpochSeconds = (times.first() + safeCursor.toDouble()).toLong()"))
        assertTrue(map.contains("SatelliteFrameSelectionPolicy.clouds("))
        assertTrue(map.contains("SatelliteFrameSelectionPolicy.lightning("))
        assertFalse(map.contains("EumetViewRepository.verifyFrame"))
        assertFalse(map.contains("LaunchedEffect(satellitePlaceKey, desiredCloudFrame?.frameIdentity)"))
        assertTrue(map.contains("SatelliteFrameWindowPolicy.frames("))
        assertTrue(map.contains("satelliteFrameStore.prepare(requests)"))
        assertTrue(map.contains("SatellitePreparedFramePolicy.desired(plan.frames, desiredCloudFrame)"))
        assertTrue(map.contains("SatellitePreparedFramePolicy.desired(plan.frames, desiredLightningFrame)"))
        assertTrue(map.contains("satelliteBuffers.reconcile(\n                style, satelliteRequests, enabledSatelliteLayers, isPlaying"))
        assertTrue(map.contains("state.desired = request.desired"))
        assertTrue(map.contains("state.active?.renderIdentity == desiredRenderIdentity"))
        assertTrue(map.contains("state.pending?.renderIdentity == desiredRenderIdentity"))
        assertTrue(map.contains("startLatestIfNeeded(style, choice, state)"))
        assertFalse(map.contains("startWarmersIfNeeded(style, choice, state)"))
        assertFalse(map.contains("val warmers: LinkedHashMap<String, SatelliteBufferSlot>"))
        assertTrue(map.contains("var retiring: SatelliteBufferSlot? = null"))
        assertTrue(map.contains("SatelliteFrameStoreProvider.get(File(applicationContext.cacheDir, \"satellite-frames\"))"))
        assertTrue(map.contains("ImageSource(sourceId, quad, bitmap)"))
        assertFalse(map.contains("ImageSource(sourceId, quad, URI(request.url))"))
        assertFalse(map.contains("RasterSource("))
        assertFalse(map.contains("TileSet("))
        assertFalse(map.contains("MapView.OnSourceChangedListener { sourceId ->"))
        assertFalse(map.contains("satelliteBuffers.onSourceChanged(sourceId)"))
        assertTrue(map.contains("withContext(Dispatchers.IO)"))
        assertTrue(map.contains("private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"))
        assertTrue(map.contains("PropertyFactory.rasterOpacity(opacity)"))
        assertTrue(map.contains("PropertyFactory.rasterFadeDuration(0f)"))
        assertTrue(map.contains("PRELOAD_OPACITY = 0.001f"))
        assertTrue(map.contains("state.retiring = outgoing"))
        assertTrue(map.contains("state.revealedAtRenderSequence = renderSequence"))
        assertTrue(map.contains("SatelliteHandoffPolicy.mayRetire("))
        assertTrue(map.contains("listOfNotNull(state.active, state.pending, state.retiring)"))
        assertTrue(map.contains("if (fully && !teardown.isClosed) satelliteBuffers.onFullyRendered()"))
        assertTrue(map.contains("SatelliteProgressiveLoadPolicy.whenReady("))
        assertTrue(map.contains("SatellitePreparationStatus.Preparing(0, requests.size)"))
        assertTrue(map.contains("currentSatellitePreparation(choice, SatellitePreparationStatus.Rendering)"))
        assertTrue(map.contains("onFrameRendered(choice)"))
        assertTrue(map.contains("currentSatellitePreparation(layer, SatellitePreparationStatus.Ready)"))
        assertFalse(map.contains("satelliteWindow.forEach { add(style"))
        assertFalse(map.contains("applySatelliteLayers("))
    }

    @Test fun `prepared frame selection never chooses a future observation`() {
        val frames = listOf(
            cachedSatellite(EumetProduct.LIGHTNING, 1_000L),
            cachedSatellite(EumetProduct.LIGHTNING, 1_300L),
            cachedSatellite(EumetProduct.LIGHTNING, 1_600L),
        )
        fun requested(time: Long) = EumetLayerMetadata(
            RadarMapLayer.LIGHTNING, time, -15.0, 47.2, 6.0, 63.3,
            EumetProduct.LIGHTNING, availableFromEpochSeconds = 0L,
            latestEpochSeconds = 3_000L, cadenceSeconds = 300L,
        )
        assertNull(SatellitePreparedFramePolicy.desired(frames, null))
        assertNull(SatellitePreparedFramePolicy.desired(frames, requested(900L)))
        assertEquals(1_300L,
            SatellitePreparedFramePolicy.desired(frames, requested(1_450L))?.validEpochSeconds)
        assertEquals(1_600L,
            SatellitePreparedFramePolicy.desired(frames, requested(2_000L))?.validEpochSeconds)
    }

    @Test fun `satellite cache uses supported budget and region identity never follows camera`() {
        val map = source("RadarImageMap.kt")
        val activity = listOf(
            File("src/main/java/com/rainalarm/app/MainActivity.kt"),
            File("app/src/main/java/com/rainalarm/app/MainActivity.kt"),
        ).first(File::isFile).readText()
        assertTrue(map.contains("satelliteAllowanceBytes = 128L * 1024L * 1024L"))
        assertTrue(map.contains("baseMapAllowanceBytes = 64L * 1024L * 1024L"))
        assertTrue(map.contains("setMaximumAmbientCacheSize("))
        assertTrue(activity.contains("SatelliteAmbientCache.configure(applicationContext)"))
        assertTrue(map.contains("SatelliteRegionPolicy.select(mapPlace)"))
        assertTrue(map.contains("SatelliteRegionPolicy.clip(it, satelliteRegion)"))
        assertTrue(map.contains("SatelliteCacheContext(satelliteRegion)"))
        assertFalse(map.contains("SatelliteTileCoverPolicy"))
        assertFalse(map.contains("SatelliteRenderMode"))
        assertFalse(map.contains("SatelliteViewport"))
        val cameraMove = map.substringAfter("val listener = MapLibreMap.OnCameraMoveListener {")
            .substringBefore("cameraListener = listener")
        assertFalse(cameraMove.contains("satellite"))
        assertTrue(map.contains("SatelliteRegionalImagePolicy.request(metadata)"))
        assertTrue(map.contains("SatelliteFrameStoreProvider.get("))
        assertEquals(0, Regex("val warmers: LinkedHashMap<String, SatelliteBufferSlot>").findAll(map).count())
        assertEquals(1, Regex("var active: SatelliteBufferSlot\\? = null").findAll(map).count())
        assertEquals(1, Regex("var pending: SatelliteBufferSlot\\? = null").findAll(map).count())
        assertEquals(1, Regex("var retiring: SatelliteBufferSlot\\? = null").findAll(map).count())
        assertTrue(map.contains("onSatellitePreparation: (RadarMapLayer, SatellitePreparationStatus?)"))
        val cache = listOf(
            File("src/main/java/com/rainalarm/app/data/SatelliteFrameCache.kt"),
            File("app/src/main/java/com/rainalarm/app/data/SatelliteFrameCache.kt"),
        ).first(File::isFile).readText()
        assertTrue(cache.contains("maximumBytes: Long = 128L * 1024L * 1024L"))
        assertTrue(cache.contains("private val downloadSlots = Semaphore(concurrency)"))
        val radar = source("RadarScreen.kt")
        assertTrue(radar.contains("Preparing ${'$'}{layer.label} ${'$'}{status.ready}/${'$'}{status.total}"))
        assertTrue(radar.contains("Modifier.align(Alignment.BottomEnd)"))
    }

    @Test fun `wind model timeline is one bounded request and changes only at fifteen minute steps`() {
        val radar = source("RadarScreen.kt")
        val map = source("RadarImageMap.kt")
        val data = listOf(
            File("src/main/java/com/rainalarm/app/data/WeatherLayers.kt"),
            File("app/src/main/java/com/rainalarm/app/data/WeatherLayers.kt"),
        ).first(File::isFile).readText()
        assertTrue(radar.contains("WindTimelinePolicy.requestWindow("))
        assertTrue(radar.contains("WeatherLayerRepository.wind(\n                viewport, windTimelineWindow"))
        assertTrue(map.contains("windGrid?.displayedAt(satelliteDisplayEpochSeconds)"))
        assertTrue(map.contains("windView.update(displayedWindGrid, windArrowScale)"))
        assertTrue(data.contains("minutely_15=wind_speed_10m,wind_direction_10m"))
        assertTrue(data.contains("start_minutely_15="))
        assertTrue(data.contains("end_minutely_15="))
        assertTrue(data.contains("times.size <= 33"))
        assertFalse(map.contains("windView.update(windGrid, windArrowScale)"))
    }

    @Test fun `verified clouds stay visible only for the same covered fresh place`() {
        val now = Instant.parse("2026-09-17T03:00:00Z").epochSecond
        val cardiff = SavedPlace("Cardiff", 51.48, -3.18)
        val london = SavedPlace("London", 51.5, -0.12)
        val clouds = EumetLayerMetadata(
            RadarMapLayer.FOG, now - 600, -20.0, 30.0, 20.0, 70.0,
            EumetProduct.CLOUD_TYPE,
        )
        val key = CloudsSwapPolicy.placeKey(cardiff)
        assertTrue(CloudsSwapPolicy.canRetain(clouds, key, cardiff, now))
        assertFalse(CloudsSwapPolicy.canRetain(clouds, key, london, now))
        assertFalse(CloudsSwapPolicy.canRetain(clouds, null, cardiff, now))
        assertFalse(CloudsSwapPolicy.canRetain(clouds, key, cardiff, now + 3_001))
    }

    @Test fun `wind field doubles arrow geometry without changing sampled point count`() {
        assertEquals(44f, WindArrowGeometry.lengthPx, 0f)
        assertEquals(16f, WindArrowGeometry.headBackPx, 0f)
        assertEquals(12f, WindArrowGeometry.headHalfWidthPx, 0f)
        assertTrue(WindArrowGeometry.cullMarginPx >= WindArrowGeometry.lengthPx / 2f +
            WindArrowGeometry.headHalfWidthPx)
        val map = source("RadarImageMap.kt")
        assertTrue(map.contains("for ((index, sample) in points.withIndex())"))
        assertTrue(map.contains("val length = WindArrowGeometry.length(arrowScale)"))
        assertTrue(map.contains("value?.renderCoordinates()?.map"))
        assertEquals(88f, WindArrowGeometry.length(2f), 0f)
        assertEquals(8f, WindArrowGeometry.headBack(0.5f), 0f)
        val settings = source("SettingsScreen.kt")
        assertTrue(settings.contains("if (RadarMapLayer.WIND in enabledMapLayers)"))
        assertTrue(settings.contains("WindArrowPreview(previewScale)"))
        assertTrue(settings.contains("selectWindArrowScale(previewScale)"))
        assertTrue(map.contains("windView.update(displayedWindGrid, windArrowScale)"))
    }

    @Test fun `Now compass and graph appearance wrap ready and placeholder cards independently`() {
        val now = source("NowScreen.kt")
        val settings = source("SettingsScreen.kt")
        assertEquals(2, Regex("NowCardTheme\\(compassAppearance\\)").findAll(now).count())
        assertEquals(2, Regex("NowCardTheme\\(graphAppearance\\)").findAll(now).count())
        assertTrue(now.contains("NowCardPalettePolicy.resolve(mode, LocalRainAlarmPalette.current)"))
        assertTrue(now.contains("MaterialTheme(colorScheme = palette.materialScheme(), content = content)"))
        assertTrue(now.contains("refreshStatus, compassAppearance, graphAppearance)"))
        assertTrue(settings.contains("NowCardAppearanceChoice(\"Compass card\""))
        assertTrue(settings.contains("NowCardAppearanceChoice(\"Graph card\""))
    }

    @Test fun `appearance settings use four aligned columns and Slate excludes only App`() {
        assertEquals(4, AppearanceGridPolicy.columnCount)
        assertEquals(listOf(AppearanceMode.DARK, AppearanceMode.LIGHT,
            AppearanceMode.FOLLOW_SYSTEM, null), AppearanceGridPolicy.appColumns)
        assertEquals(listOf(AppearanceMode.DARK, AppearanceMode.LIGHT,
            AppearanceMode.FOLLOW_SYSTEM, AppearanceMode.SLATE), AppearanceGridPolicy.mapColumns)
        assertEquals(listOf(NowCardAppearance.DARK, NowCardAppearance.LIGHT,
            NowCardAppearance.FOLLOW_APP, NowCardAppearance.SLATE), AppearanceGridPolicy.cardColumns)
        assertFalse(AppearanceMode.SLATE in AppearanceGridPolicy.appColumns)
        assertTrue(AppearanceMode.SLATE in AppearanceGridPolicy.mapColumns)
        assertTrue(NowCardAppearance.SLATE in AppearanceGridPolicy.cardColumns)
        assertEquals(null, AppearanceGridPolicy.appColumns[3])

        val settings = source("SettingsScreen.kt")
        val repository = source("../data/RadarProviders.kt")
        assertTrue(settings.contains("AppearanceChoice(\"App\", appAppearance, AppearanceGridPolicy.appColumns"))
        assertTrue(settings.contains("AppearanceChoice(\"Map\", mapAppearance, AppearanceGridPolicy.mapColumns"))
        assertTrue(settings.contains("if (mode == null) Spacer(Modifier.weight(1f))"))
        assertTrue(settings.contains("require(columns.size == AppearanceGridPolicy.columnCount)"))
        assertTrue(settings.contains("maxLines = 2"))
        assertFalse(settings.contains("horizontalScroll"))
        assertTrue(repository.contains("AppearanceMode.decodeApp(it[appAppearanceKey])"))
        assertTrue(repository.contains("mode.takeUnless { it == AppearanceMode.SLATE } ?: AppearanceMode.DARK"))
    }

    @Test fun `Slate card palette uses the shared base and dark high contrast scheme`() {
        val appearance = source("RainAlarmAppearance.kt")
        assertTrue(appearance.contains("fun materialScheme(): ColorScheme = if (usesDarkMaterialScheme) darkColorScheme("))
        assertTrue(appearance.contains("val SlateRainPalette = RainAlarmPalette("))
        assertTrue(appearance.contains("usesDarkMaterialScheme = true,"))
        assertTrue(appearance.contains("background = Color(0xFF45516E), surface = Color(0xFF45516E), elevated = Color(0xFF45516E)"))
        assertTrue(appearance.contains("text = Color.White, muted = Color(0xFFD7DCE7), accent = Color(0xFF4FC3F7)"))
        assertTrue(appearance.contains("border = Color(0xFF9EAAC1)"))
        assertTrue(appearance.contains("NowCardAppearance.SLATE -> SlateRainPalette"))
        assertTrue(contrast(0xFFFFFFFF, 0xFF45516E) >= 4.5)
        assertTrue(contrast(0xFFD7DCE7, 0xFF45516E) >= 4.5)
        assertTrue(contrast(0xFF9EAAC1, 0xFF45516E) >= 3.0)
    }

    @Test fun `card appearance changes do not replace precipitation or chart rendering rules`() {
        val now = source("NowScreen.kt")
        assertTrue(now.contains("NowPeakRainColorPolicy.forSeries(series, analysis)"))
        assertTrue(now.contains("R.drawable.now_rain_drops"))
        assertTrue(now.contains("R.drawable.now_snowflakes"))
        assertTrue(now.contains("drawRect(Color(series.displayColor(0.88f)).copy(alpha = 0.045f)"))
        assertTrue(now.contains("series.chartSeverity(point.maximum)"))
        assertTrue(now.contains("series.chartSeverity(point.minimum)"))
    }

    private fun contrast(foreground: Long, background: Long): Double {
        fun luminance(argb: Long): Double {
            fun linear(channel: Long): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92
                else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            val red = linear((argb shr 16) and 0xff)
            val green = linear((argb shr 8) and 0xff)
            val blue = linear(argb and 0xff)
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue
        }
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test fun `resolved map style identity reaches map rendering while control contrast stays dark for Slate`() {
        val main = source("../MainActivity.kt")
        val radar = source("RadarScreen.kt")
        val map = source("RadarImageMap.kt")
        assertTrue(main.contains("mapStyle = mapAppearance.resolveMapStyle(systemDark)"))
        assertTrue(radar.contains("val darkMap = mapStyle.darkControls"))
        assertTrue(radar.contains("mapStyle = mapStyle"))
        assertTrue(map.contains("DisposableEffect(map, mapStyle)"))
        assertTrue(map.contains("ready.setStyle(RadarMapAppearance.styleUrl(mapStyle))"))
    }

    @Test fun `likely snow setting is visible only for explicitly selected open radar`() {
        val settings = source("SettingsScreen.kt")
        val main = source("../MainActivity.kt")
        val radar = source("RadarScreen.kt")
        assertTrue(settings.contains("if (selectedProvider == RadarProviderKind.OPEN_RAINVIEWER)"))
        assertTrue(settings.contains("Text(\"Show likely snow\""))
        assertTrue(settings.contains("Switch(checked = showLikelySnow"))
        assertTrue(main.contains("showLikelySnow = showLikelySnow"))
        assertTrue(radar.contains("LaunchedEffect(RadarLiveSessionPolicy.loadIdentity(place), reload, showLikelySnow)"))
    }
}
