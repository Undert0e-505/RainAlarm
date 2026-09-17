package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherUiWiringTest {
    private fun source(name: String) = listOf(
        File("src/main/java/com/rainalarm/app/ui/$name"),
        File("app/src/main/java/com/rainalarm/app/ui/$name"),
    ).first(File::isFile).readText()

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
