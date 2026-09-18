package com.rainalarm.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(0xFF111C24.toInt(), RadarMapAppearance.loadingBackgroundArgb(true))
        assertEquals(0xFFF3F5F4.toInt(), RadarMapAppearance.loadingBackgroundArgb(false))
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

    @Test fun mapViewCoverAndListenersAreScopedToTheSessionInstance() {
        val source = listOf(
            File("src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/RadarImageMap.kt"),
        ).first(File::isFile).readText()
        assertTrue(source.contains("key(session)"))
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
        assertTrue(source.contains(".foregroundLoadColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))"))
        assertTrue(source.contains("container.setBackgroundColor(RadarMapAppearance.loadingBackgroundArgb(darkMap))"))
        assertTrue(source.contains("mapView.removeOnWillStartRenderingFrameListener(startingListener)"))
        assertTrue(source.contains("mapView.removeOnDidFinishRenderingFrameListener(renderedListener)"))
        assertTrue(source.contains("mapView.removeOnDidFailLoadingMapListener(failedListener)"))
        assertTrue(source.contains("mapRevealGate.markFailed()"))
    }
}
