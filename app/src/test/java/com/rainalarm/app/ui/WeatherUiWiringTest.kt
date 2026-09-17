package com.rainalarm.app.ui

import com.rainalarm.app.data.PlaceCollection
import com.rainalarm.app.data.PlaceCollectionRules
import com.rainalarm.app.data.SavedPlace
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
        assertTrue(placesUi.contains("onDelete = { deletePlace(place.id) }"))
        assertTrue(placesUi.contains("IconButton(onClick = onDelete)"))
        assertTrue(placesUi.contains("detectDragGesturesAfterLongPress("))
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
        assertFalse(map.contains("canvas.drawText(\"${'$'}{speed.roundToInt()}\""))
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
        assertTrue(now.contains("modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp)"))
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
        assertEquals(10, RadarTopControlsPolicy.windChipSp(328))
        assertTrue(RadarTopControlsPolicy.windChipBelowControls(328))
        assertFalse(RadarTopControlsPolicy.windChipBelowControls(360))
        assertFalse(RadarTopControlsPolicy.windChipBelowControls(420))
        val radar = source("RadarScreen.kt")
        assertFalse(radar.contains("top = if (mapLayer == RadarMapLayer.WIND) 56.dp"))
        assertTrue(radar.contains("top = RadarTopControlsPolicy.timeTopDp.dp"))
        assertTrue(radar.contains("if (windBelow) RadarWindChip"))
        assertTrue(radar.contains("if (mapLayer == RadarMapLayer.WIND && !windBelow) RadarWindChip"))
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
        assertTrue(now.contains("NowCardTheme(compassAppearance)"))
        assertTrue(now.contains("NowCardTheme(graphAppearance)"))
        assertTrue(now.contains("MaterialTheme(colorScheme = palette.materialScheme(), content = content)"))
        assertTrue(now.contains("refreshStatus, compassAppearance, graphAppearance)"))
        assertTrue(settings.contains("NowCardAppearanceChoice(\"Compass card\""))
        assertTrue(settings.contains("NowCardAppearanceChoice(\"Graph card\""))
    }
}
