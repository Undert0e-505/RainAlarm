package com.rainalarm.app.ui

import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.SavedPlace
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.NowCardAppearance
import java.io.File
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

    @Test fun `radar refresh reloads session and layer without replacing camera memory`() {
        val radar = source("RadarScreen.kt")
        assertTrue(radar.contains("onRefresh = { reload++; layerRefresh++; refreshPointWeather() }"))
        assertTrue(radar.contains("cameraMemory = cameraMemory"))
        assertTrue(radar.contains("RadarMapLayer.entries.forEach"))
        assertTrue(radar.contains("mapLayer == RadarMapLayer.OFF -> AncillaryStatus.Off"))
        assertTrue(radar.contains("onWindViewportChanged = { windViewport = it }"))
        val map = source("RadarImageMap.kt")
        assertTrue(map.contains("TileSet(\"2.2.0\", satellite.tileUrl())"))
        assertTrue(map.contains("sourceId == SATELLITE_SOURCE_ID"))
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

    @Test fun `wind controls keep forecast time in the same top row`() {
        assertEquals(12, RadarTopControlsPolicy.timeTopDp)
        assertEquals(18, RadarTopControlsPolicy.timeLabelSp(328))
        assertEquals(20, RadarTopControlsPolicy.timeLabelSp(400))
        assertEquals(112, RadarTopControlsPolicy.timeLabelMaxWidthDp(328, true))
        assertEquals(160, RadarTopControlsPolicy.timeLabelMaxWidthDp(328, false))
        assertEquals(220, RadarTopControlsPolicy.statusMaxWidthDp(400))
        val radar = source("RadarScreen.kt")
        assertFalse(radar.contains("top = if (mapLayer == RadarMapLayer.WIND) 56.dp"))
        assertTrue(radar.contains("top = RadarTopControlsPolicy.timeTopDp.dp"))
        assertTrue(radar.contains(".widthIn(max = RadarTopControlsPolicy.timeLabelMaxWidthDp("))
        assertTrue(radar.contains("RadarLayerStatus(mapLayer, ancillaryStatus, currentWeather, mapWidthDp"))
        assertTrue(radar.contains("top = RadarTopControlsPolicy.statusTopDp.dp"))
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

    @Test fun `follow and ancillary status use fixed map control positions`() {
        val radar = source("RadarScreen.kt")
        val controls = radar.substringAfter(
            "Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically)",
        ).substringBefore("RadarLayerStatus(mapLayer")
        val follow = controls.indexOf("Stop following live location")
        val layers = controls.indexOf("Map layers, ${'$'}{mapLayer.label}")
        val refresh = controls.indexOf("Refresh radar and map layer")
        val centre = controls.indexOf("Use current device location")
        assertTrue(follow >= 0)
        assertTrue(follow < layers)
        assertTrue(layers < refresh)
        assertTrue(refresh < centre)
        assertTrue(controls.contains("tint = if (followLive) Accent.copy(alpha = 0.85f)"))
        assertTrue(controls.contains("else if (darkMap) Color.White else Color.Black"))
        assertFalse(controls.contains(".background(if (followLive)"))
        assertTrue(radar.contains("\"Lightning loading\""))
        assertTrue(radar.contains("\"Lightning available\""))
        assertTrue(radar.contains("\"Lightning unavailable\""))
        assertTrue(radar.contains("\"Fog loading\""))
        assertTrue(radar.contains("\"Fog available\""))
        assertTrue(radar.contains("\"Fog unavailable\""))
        assertFalse(radar.contains("Wind · Open-Meteo model"))
        assertFalse(radar.contains("m ago"))
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
        assertTrue(settings.contains("if (mapLayer == RadarMapLayer.WIND)"))
        assertTrue(settings.contains("WindArrowPreview(previewScale)"))
        assertTrue(settings.contains("selectWindArrowScale(previewScale)"))
        assertTrue(map.contains("windView.update(windGrid, windArrowScale)"))
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
