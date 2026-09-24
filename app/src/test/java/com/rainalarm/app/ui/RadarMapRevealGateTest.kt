package com.rainalarm.app.ui

import com.rainalarm.app.data.RadarMapStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarMapRevealGateTest {
    @Test fun partialAndPreStyleFramesCannotRevealTheCover() {
        val gate = RadarMapRevealGate()
        val generation = gate.styleRequested()
        assertTrue(gate.isCovered)
        gate.frameStarted()
        assertFalse(gate.frameRendered(true)) // A complete default frame is still not our style.
        gate.frameStarted()
        assertFalse(gate.frameRendered(false))
        assertTrue(gate.styleLoaded(generation))
        assertFalse(gate.frameRendered(true)) // This frame started before the style callback.
        gate.frameStarted()
        assertFalse(gate.frameRendered(false)) // Styled but incomplete tiles must stay covered.
        assertFalse(gate.frameRendered(true)) // An unmatched finish cannot reuse that start.
        assertTrue(gate.isCovered)
        gate.frameStarted()
        assertTrue(gate.frameRendered(true))
        assertFalse(gate.isCovered)
        assertFalse(gate.frameRendered(true))
    }

    @Test fun fastCompleteFrameAfterStyleRevealsWithoutExtraDelay() {
        val gate = RadarMapRevealGate()
        val generation = gate.styleRequested()
        assertTrue(gate.styleLoaded(generation))
        gate.frameStarted()
        assertTrue(gate.frameRendered(true))
        assertFalse(gate.isCovered)
    }

    @Test fun themeChangeRejectsSupersededStyleAndQueuedFrame() {
        val gate = RadarMapRevealGate()
        val dark = gate.styleRequested()
        assertTrue(gate.styleLoaded(dark))
        gate.frameStarted()
        assertTrue(gate.frameRendered(true))
        val light = gate.styleRequested()
        assertTrue(gate.isCovered)
        assertFalse(gate.styleLoaded(dark))
        gate.frameStarted()
        assertFalse(gate.frameRendered(true))
        assertTrue(gate.styleLoaded(light))
        assertFalse(gate.frameRendered(true)) // A pre-callback start cannot qualify.
        gate.frameStarted()
        assertTrue(gate.frameRendered(true))
        assertFalse(gate.isCovered)
        assertEquals(0xFF111C24.toInt(),
            RadarMapAppearance.loadingBackgroundArgb(RadarMapStyle.DARK))
        assertEquals(0xFFF3F5F4.toInt(),
            RadarMapAppearance.loadingBackgroundArgb(RadarMapStyle.LIGHT))
        assertEquals(0xFF45516E.toInt(),
            RadarMapAppearance.loadingBackgroundArgb(RadarMapStyle.SLATE))
    }

    @Test fun failureKeepsTheCoverButReportsItAndCanRecover() {
        val gate = RadarMapRevealGate()
        val generation = gate.styleRequested()
        assertTrue(gate.markFailed())
        assertTrue(gate.isCovered)
        assertTrue(gate.hasFailed)
        assertFalse(gate.markFailed())
        assertTrue(gate.styleLoaded(generation))
        gate.frameStarted()
        assertFalse(gate.frameRendered(false))
        gate.frameStarted()
        assertTrue(gate.frameRendered(true)) // A late complete style clears the error.
        assertFalse(gate.hasFailed)
        assertFalse(gate.isCovered)
    }

    @Test fun mapViewCoverAndListenersOutliveNullableRadarSessions() {
        val source = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
        ).first(File::isFile).readText()
        assertFalse(source.contains("key(session)"))
        assertTrue(source.contains("session: RadarSession?"))
        assertTrue(source.contains("val mapView = remember {"))
        assertTrue(source.contains("val desiredRadarSlot = remember(session)"))
        assertTrue(source.contains("val teardown = remember(mapView)"))
        assertTrue(source.contains("private class RadarBaseMarkerView"))
        assertTrue(source.contains("baseMarkerView.visibility = if (activeRadarSlot == null)"))
        assertTrue(source.contains("addView(mapCover"))
        assertTrue(source.indexOf("addView(mapCover") < source.indexOf("mapView.getMapAsync"))
        val mapAttach = requireNotNull(Regex("addView\\(\\s*mapView,").find(source)).range.first
        assertTrue(source.indexOf("mapView.addOnWillStartRenderingFrameListener(startingListener)") < mapAttach)
        assertTrue(source.indexOf("mapView.addOnDidFinishRenderingFrameListener(renderedListener)") < mapAttach)
        assertTrue(source.indexOf("mapView.addOnDidFailLoadingMapListener(failedListener)") <
            source.indexOf("mapView.getMapAsync"))
        assertTrue(source.contains("val styleGeneration = mapRevealGate.styleRequested()"))
        assertTrue(source.contains("mapRevealGate.styleLoaded(styleGeneration)"))
        assertTrue(source.contains("MapView.OnWillStartRenderingFrameListener"))
        assertTrue(source.contains("MapView.OnDidFinishRenderingFrameListener"))
        assertTrue(source.contains("mapRevealGate.frameStarted()"))
        assertTrue(source.contains("mapRevealGate.frameRendered(fully)"))
        assertTrue(source.contains(".foregroundLoadColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))"))
        assertTrue(source.contains("container.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(mapStyle))"))
        assertTrue(source.contains("mapView.removeOnWillStartRenderingFrameListener(startingListener)"))
        assertTrue(source.contains("mapView.removeOnDidFinishRenderingFrameListener(renderedListener)"))
        assertTrue(source.contains("mapView.removeOnDidFailLoadingMapListener(failedListener)"))
        assertTrue(source.contains("mapRevealGate.markFailed()"))
    }

    @Test fun darkAndSlateAreDistinctStyleChangesWithoutRecreatingSessionOrCamera() {
        val mapSource = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
        ).first(File::isFile).readText()
        val screenSource = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarScreen.kt"),
        ).first(File::isFile).readText()
        assertFalse(mapSource.contains("key(session)"))
        assertFalse(mapSource.contains("key(session, mapStyle)"))
        assertTrue(mapSource.contains("DisposableEffect(map, mapStyle)"))
        assertTrue(mapSource.contains("ready.setStyle(RadarMapAppearance.styleUrl(mapStyle))"))
        assertTrue(mapSource.contains("val target = cameraMemory.target(cameraPlace, mapWidth, recenterSignal)"))
        assertTrue(screenSource.contains("var cursor by remember(session)"))
        assertFalse(screenSource.contains("remember(session, mapStyle)"))
        assertFalse(screenSource.contains("LaunchedEffect(RadarLiveSessionPolicy.loadIdentity(place), reload, showLikelySnow, mapStyle)"))
    }

    @Test fun darkAndSlateLiftKnownTextLabelsWithDistinctReadableHierarchy() {
        val dark = RadarMapLabelContrastPolicy.paletteFor(RadarMapStyle.DARK)
        val slate = RadarMapLabelContrastPolicy.paletteFor(RadarMapStyle.SLATE)
        assertNotNull(dark)
        assertNotNull(slate)
        assertNull(RadarMapLabelContrastPolicy.paletteFor(RadarMapStyle.LIGHT))
        assertEquals(RadarMapLabelCategory.PLACE,
            RadarMapLabelContrastPolicy.category("place_village", "place"))
        assertEquals(RadarMapLabelCategory.WATER,
            RadarMapLabelContrastPolicy.category("water_name", "water_name"))
        assertEquals(RadarMapLabelCategory.ROAD,
            RadarMapLabelContrastPolicy.category("highway_name_other", "transportation_name"))
        assertEquals(RadarMapLabelCategory.ROAD_REFERENCE,
            RadarMapLabelContrastPolicy.category("highway_ref", "transportation_name"))
        assertEquals(RadarMapLabelCategory.ROAD_REFERENCE,
            RadarMapLabelContrastPolicy.category("highway_name_motorway", "transportation_name"))
        assertNull(RadarMapLabelContrastPolicy.category("poi_icon", "poi"))

        listOf(requireNotNull(dark) to 0xFF0C0C0C.toInt(),
            requireNotNull(slate) to 0xFF45516E.toInt()).forEach { (palette, background) ->
            listOf(palette.placeTextArgb, palette.roadTextArgb, palette.waterTextArgb,
                palette.roadReferenceTextArgb).forEach { foreground ->
                assertTrue(contrast(foreground, background) >= 4.5)
            }
            assertTrue(luminance(palette.placeTextArgb) > luminance(palette.roadTextArgb))
            assertTrue(luminance(palette.placeTextArgb) > luminance(palette.roadReferenceTextArgb))
            assertNotEquals(palette.placeTextArgb, palette.waterTextArgb)
            assertTrue(palette.haloWidth > 0f)
        }
    }

    @Test fun labelContrastRunsInsideEachDarkOrSlateStyleCallbackBeforeReveal() {
        val source = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
        ).first(File::isFile).readText()
        val callback = source.substring(source.indexOf("ready.setStyle(RadarMapAppearance.styleUrl(mapStyle))"))
        assertTrue(callback.contains("if (RadarMapLabelContrastPolicy.paletteFor(mapStyle) != null)"))
        assertTrue(callback.contains("applyMapLabelContrast(it, mapStyle)"))
        assertTrue(callback.indexOf("applyMapLabelContrast(it, mapStyle)") <
            callback.indexOf("mapRevealGate.styleLoaded(styleGeneration)"))
        assertTrue(source.contains("if (layer.textField.isNull) return@forEach"))
        assertTrue(source.contains("style.layers.filterIsInstance<SymbolLayer>()"))
        assertTrue(source.contains("PropertyFactory.textColor(palette.textArgb(category))"))
        assertTrue(source.contains("PropertyFactory.textHaloColor(palette.haloArgb)"))
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92
            else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear((argb ushr 16) and 0xff) +
            0.7152 * linear((argb ushr 8) and 0xff) +
            0.0722 * linear(argb and 0xff)
    }
}
